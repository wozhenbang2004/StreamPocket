package dev.streampocket;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** SDK-free regression checks for pasted links, source diagnostics and request admission. */
public final class DownloadFlowSelfTest {
    public static void main(String[] args) throws Exception {
        screenshotTimeout();
        if (args.length > 0) return;
        links(); failures(); scheduler();
        System.out.println("PASS: screenshot timeout, batch order/dedup/video indices, invalid links, cooldown isolation, cancellation");
    }
    static void screenshotTimeout() {
        String error = "默认解析:\n解析器退出码 1\nERROR: [twitter] 2108499461219733583: Unable to download JSON metadata: timed out (caused by TransportError('timed out'))\n\n检测到 X 登录凭据；本次保留该登录状态，没有改用匿名访问。失效 Cookie 可在设置中清除或重新导入。";
        check(CoreRules.inspectionMessage(new Exception(error)).contains("超时"), "Network timeout was misreported as login failure");
    }
    static void links() {
        LinkBatch.Result batch=LinkBatch.parse("1. https://x.com/one/status/123?s=20\n重复：https://twitter.com/one/status/123?t=abc。B站：https://b23.tv/abc，抖音 https://v.douyin.com/abc/！");
        check(batch.urls.equals(Arrays.asList("https://x.com/one/status/123?s=20","https://b23.tv/abc","https://v.douyin.com/abc/")), "Mixed share order changed");
        check(batch.duplicates==1,"X aliases were not deduplicated");
        check(LinkBatch.parse("https://x.com/u/status/123/video/1 https://x.com/u/status/123/video/2").urls.size()==2,"Different videos in a tweet were dropped");
        check(LinkBatch.parse("https://x.com/u/status/123https://x.com/u/status/456").urls.size()==2,"Adjacent links were swallowed");
        check(LinkBatch.parse("x.com/u/status/123\nwww.bilibili.com/video/BV123?p=2").urls.size()==2,"Common scheme-less links were missed");
        check(LinkBatch.parse("https://www.bilibili.com/video/BV123?p=1&vd_source=a https://www.bilibili.com/video/BV123?p=2&vd_source=b").urls.size()==2,"Bili parts merged");
        check(LinkBatch.parse("https://example.com/v?s=123 https://example.com/v?s=456").urls.size()==2,"Generic query tokens merged");
        check(LinkBatch.parse("https://127.0.0.1/a https://x.com/u/status/123").invalid.size()==1,"Invalid local link was not reported");
        StringBuilder many=new StringBuilder();for(int n=0;n<101;n++)many.append("https://x.com/u/status/").append(n).append('\n');
        try{LinkBatch.parse(many.toString());throw new AssertionError("Oversized batch was silently truncated");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("100"),"Missing batch limit explanation");}
    }
    static void failures() {
        check(CoreRules.inspectionMessage(new Exception("ERROR: No video could be found in this tweet\nERROR: timed out")).contains("超时"),"An earlier API error hid the final timeout");
        check(SourceFailure.kind("ERROR: [twitter] 210429973: timed out")==SourceFailure.Kind.TIMEOUT,"Tweet digits confused with HTTP 429");
        check(SourceFailure.kind("HTTP Error 429: Too Many Requests")==SourceFailure.Kind.RATE_LIMIT,"HTTP limit missed");
        check(SourceFailure.kind("ERROR: fresh cookies required")==SourceFailure.Kind.OTHER,"Generic cookie advice asserted expired auth");
        check(SourceFailure.kind("ERROR: HTTP Error 401: Unauthorized")==SourceFailure.Kind.AUTH,"Explicit auth failure missed");
        check(SourceFailure.kind("ERROR: login required\nERROR: timed out")==SourceFailure.Kind.TIMEOUT,"Earlier attempt hid latest error");
        check(!SourceFailure.retryable(new Exception("HTTP Error 429")),"A platform limit must not be immediately retried");
    }
    static void scheduler() throws Exception {
        AtomicLong time=new AtomicLong(10_000);SourceScheduler scheduler=new SourceScheduler(time::get);
        String x="https://x.com/u/status/1";
        SourceScheduler.Lease first=scheduler.acquire(x,()->false,stage->{});
        first.outcome(new Exception("HTTP Error 429"));first.close();
        ExecutorService pool=Executors.newSingleThreadExecutor();AtomicBoolean cancel=new AtomicBoolean();CountDownLatch waiting=new CountDownLatch(1);
        try {
            Future<Boolean> next=pool.submit(()->{try(SourceScheduler.Lease lease=scheduler.acquire(x,cancel::get,stage->waiting.countDown())){return true;}catch(InterruptedException expected){return false;}});
            check(waiting.await(2,TimeUnit.SECONDS),"Limit did not defer next X request");
            try(SourceScheduler.Lease unrelated=scheduler.acquire("https://b23.tv/a",()->false,stage->{})){check(!next.isDone(),"X cooldown was skipped");}
            cancel.set(true);check(!next.get(2,TimeUnit.SECONDS),"Cancellation was lost during cooldown");
            time.addAndGet(60_000);
            try(SourceScheduler.Lease resumed=scheduler.acquire(x,()->false,stage->{})){resumed.outcome(null);}
            List<String> a=scheduler.twitterApis(),b=scheduler.twitterApis();check(!a.get(0).equals(b.get(0)),"Anonymous APIs were not balanced");
            check(a.size()==3&&a.containsAll(b),"Anonymous API pool changed");
            check(SourceScheduler.platform("https://v.douyin.com/a").equals("douyin"),"Short Douyin URL bypassed its platform gate");
        } finally {pool.shutdownNow();check(pool.awaitTermination(2,TimeUnit.SECONDS),"Test worker leaked");}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
