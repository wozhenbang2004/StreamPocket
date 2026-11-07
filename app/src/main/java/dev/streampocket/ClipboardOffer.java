package dev.streampocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.TreeSet;

/** Only a digest is retained for repeat suppression; clipboard text stays ephemeral. */
final class ClipboardOffer {
    static String fingerprint(List<String> urls) {
        return fingerprint(urls,false);
    }
    static String fingerprint(List<String> urls,boolean files) {
        try {
            TreeSet<String> identities=new TreeSet<>();for(String url:urls)identities.add(files?DirectFileRules.normalizeUrl(url):LinkBatch.identity(url));
            String payload=(files?"direct-file\n":"")+LinkBatch.join(identities,"\n");
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();
        } catch(Exception unavailable){throw new IllegalStateException(unavailable);}
    }
    static boolean alreadyInDraft(List<String> urls,String draft) {
        return alreadyInDraft(urls,draft,false);
    }
    static boolean alreadyInDraft(List<String> urls,String draft,boolean files) {
        try {return fingerprint(urls,files).equals(fingerprint((files?LinkBatch.parseFiles(draft):LinkBatch.parse(draft)).urls,files));}
        catch(IllegalArgumentException noLinks){return false;}
    }
}
