package dev.streampocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.TreeSet;

/** Only a digest is retained for repeat suppression; clipboard text stays ephemeral. */
final class ClipboardOffer {
    static String fingerprint(List<String> urls) {
        try {
            TreeSet<String> identities=new TreeSet<>();for(String url:urls)identities.add(LinkBatch.identity(url));
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(LinkBatch.join(identities,"\n").getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();
        } catch(Exception unavailable){throw new IllegalStateException(unavailable);}
    }
    static boolean alreadyInDraft(List<String> urls,String draft) {
        try {return fingerprint(urls).equals(fingerprint(LinkBatch.parse(draft).urls));}
        catch(IllegalArgumentException noLinks){return false;}
    }
}
