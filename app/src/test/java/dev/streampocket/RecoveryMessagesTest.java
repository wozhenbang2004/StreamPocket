package dev.streampocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RecoveryMessagesTest {
    @Test public void revokedSelectedFolderKeepsItsActionableLocalMessage(){
        String message="保存文件夹授权已失效，请重新选择；文件没有改存到其他位置";
        assertEquals(message,CoreRules.userMessage(new java.io.IOException(message)));
        assertFalse(CoreRules.userMessage(new java.io.IOException("opaque provider failure")).contains("opaque"));
    }
    @Test public void dnsAndConnectionFailuresExplainDifferentRecoverySteps(){
        String dns=CoreRules.inspectionMessage(new java.net.UnknownHostException("example.invalid"));assertTrue(dns.contains("DNS"));assertTrue(dns.contains("重试"));assertFalse(dns.contains("UnknownHostException"));
        assertTrue(CoreRules.inspectionMessage(new Exception("connection refused")).contains("无法连接源站"));
        assertTrue(CoreRules.inspectionMessage(new Exception("ERROR: login required\nERROR: timed out")).contains("超时"));
    }
    @Test public void permissionAndMissingFilesHaveDifferentActionableMessages(){
        String denied=CoreRules.userMessage(new SecurityException("permission denied")),missing=CoreRules.userMessage(new java.io.FileNotFoundException("file does not exist"));assertTrue(denied.contains("授予"));assertTrue(missing.contains("重新下载"));assertFalse(missing.contains("授予"));
        assertTrue(CoreRules.userMessage(new java.io.FileNotFoundException("EACCES permission denied")).contains("权限"));
    }
    @Test public void sourceAccessMissingContentAndUnavailableQualityAreNotNetworkTimeouts(){
        assertEquals(SourceFailure.Kind.ACCESS,SourceFailure.kind("HTTP Error 403: Forbidden"));assertFalse(SourceFailure.retryable(new Exception("HTTP Error 403")));assertTrue(CoreRules.inspectionMessage(new Exception("HTTP Error 404")).contains("原平台"));
        assertTrue(CoreRules.inspectionMessage(new Exception("requested format is not available")).contains("其他可用画质"));assertTrue(CoreRules.inspectionMessage(new Exception("unsupported url")).contains("视频页面链接"));
        assertEquals(SourceFailure.Kind.OTHER,SourceFailure.kind("tweet 210403404429123 was rejected"));
    }
    @Test public void certificateStorageAndUnknownFailuresKeepRecoveryAndDiagnostics(){
        assertTrue(CoreRules.userMessage(new Exception("certificate verify failed")).contains("手机时间"));assertTrue(CoreRules.userMessage(new Exception("ENOSPC no space left")).contains("腾出空间"));
        String unknown=CoreRules.inspectionMessage(new Exception("opaque extractor problem"));assertTrue(unknown.contains("链接已保留"));assertTrue(unknown.contains("重试"));assertFalse(unknown.contains("opaque"));
    }
}
