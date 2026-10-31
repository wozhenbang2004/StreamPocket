package dev.streampocket;

import java.net.URI;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned public Release contract. No remote field can choose a different repository or package. */
final class UpdateOffer {
    static final String REPO = "https://github.com/wozhenbang2004/StreamPocket";
    static final String API = "https://api.github.com/repos/wozhenbang2004/StreamPocket/releases/latest";
    static final String APK = "StreamPocket-arm64-v8a.apk", MANIFEST = "update-manifest.json";
    static final long MAX_APK_BYTES = 300L * 1024 * 1024;
    final long code, size;
    final int minSdk;
    final String version, packageName, abi, sha256, certificate, url, notes, tag;

    UpdateOffer(JSONObject manifest, String tag, String notes, String url) throws Exception {
        if (manifest.getInt("schemaVersion") != 1) throw new IllegalArgumentException("不支持的更新清单版本");
        code=manifest.getLong("versionCode");version=manifest.getString("versionName");packageName=manifest.getString("packageName");
        abi=manifest.getString("abi");minSdk=manifest.getInt("minSdk");size=manifest.getLong("apkSize");
        sha256=manifest.getString("apkSha256").toLowerCase(Locale.ROOT);
        certificate=manifest.getString("signingCertificateSha256").toLowerCase(Locale.ROOT);
        this.tag=tag;this.notes=notes==null?"":notes.substring(0,Math.min(notes.length(),12000));this.url=url;
        if(code<=0||size<=0||size>MAX_APK_BYTES||minSdk<24||version.isEmpty()||version.length()>80
                ||!sha256.matches("[a-f0-9]{64}")||!certificate.matches("[a-f0-9]{64}")
                ||!"arm64-v8a".equals(abi)||!APK.equals(manifest.getString("apkAssetName"))||!validAssetUrl(url,tag,APK))
            throw new IllegalArgumentException("更新清单或下载地址无效");
    }

    void compatible(String installedPackage, long installedCode, String installedCertificate, int sdk, String[] supportedAbis) {
        if(!packageName.equals(installedPackage))throw new IllegalArgumentException("更新包名不匹配，已拒绝");
        if(code<=installedCode)throw new IllegalArgumentException("不是更新版本，拒绝重复安装或降级");
        if(!certificate.equals(installedCertificate))throw new IllegalArgumentException("更新签名不匹配，已拒绝");
        if(minSdk>sdk||!java.util.Arrays.asList(supportedAbis).contains(abi))throw new IllegalArgumentException("当前设备不支持此更新包");
    }

    JSONObject json() throws Exception {
        return new JSONObject().put("schemaVersion",1).put("versionCode",code).put("versionName",version)
                .put("packageName",packageName).put("abi",abi).put("minSdk",minSdk).put("apkAssetName",APK)
                .put("apkSize",size).put("apkSha256",sha256).put("signingCertificateSha256",certificate)
                .put("tag",tag).put("notes",notes).put("url",url);
    }
    static UpdateOffer saved(String json) throws Exception {
        JSONObject data=new JSONObject(json);return new UpdateOffer(data,data.getString("tag"),data.optString("notes"),data.getString("url"));
    }
    static JSONObject asset(JSONObject release,String name) {
        JSONArray assets=release.optJSONArray("assets");if(assets==null)return null;
        for(int i=0;i<assets.length();i++){JSONObject item=assets.optJSONObject(i);if(item!=null&&name.equals(item.optString("name"))&&"uploaded".equals(item.optString("state")))return item;}
        return null;
    }
    static boolean validAssetUrl(String value,String tag,String name) {
        try {
            if(tag==null||!tag.matches("v[0-9][A-Za-z0-9._-]{0,79}"))return false;
            URI uri=new URI(value);
            return "https".equals(uri.getScheme())&&"github.com".equals(uri.getHost())&&uri.getPort()==-1
                    &&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null
                    &&("/wozhenbang2004/StreamPocket/releases/download/"+tag+"/"+name).equals(uri.getRawPath());
        }catch(Exception invalid){return false;}
    }
    static boolean allowedRedirect(String value) {
        try {URI uri=new URI(value);String host=uri.getHost();return "https".equals(uri.getScheme())&&uri.getUserInfo()==null
                &&(uri.getPort()==-1||uri.getPort()==443)&&host!=null
                &&(host.equals("api.github.com")||host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com"));}
        catch(Exception invalid){return false;}
    }
}
