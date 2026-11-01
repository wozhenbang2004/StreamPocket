package dev.streampocket;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

/** Compact plain release notes with semantic headings; no Markdown source in the page. */
final class ReleaseNotes {
    private ReleaseNotes(){}
    static CharSequence format(String source){
        SpannableStringBuilder out=new SpannableStringBuilder("更新内容\n\n");out.setSpan(new StyleSpan(Typeface.BOLD),0,4,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if(source==null||source.trim().isEmpty())return out.append("此版本未提供更新说明。");
        for(String raw:source.split("\n")){
            String line=raw.trim();boolean heading=line.matches("^#{1,6}\\s+.*");line=line.replaceFirst("^#{1,6}\\s+","");
            // The update screen needs changes to the app, not the distribution/build report.
            if(heading&&line.matches("(?i)(?:安装.*|下载与安装.*|验证.*|测试.*|开发.*|源码.*|开源.*|第三方.*|从源码.*|Install.*|Build.*|Validat.*|Test.*|Source.*|Develop.*|Licen.*)"))break;
            if(line.equals("更新内容")||line.equalsIgnoreCase("What's new"))continue;
            line=line.replaceAll("\\[([^]]+)\\]\\([^)]*\\)","$1").replace("**","").replace("`","");
            line=line.replaceFirst("^[-*]\\s+","•  ");int start=out.length();out.append(line).append('\n');
            if(heading&&line.length()>0)out.setSpan(new StyleSpan(Typeface.BOLD),start,start+line.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }
}
