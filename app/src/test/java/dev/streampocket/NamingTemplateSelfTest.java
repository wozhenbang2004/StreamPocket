package dev.streampocket;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Runs the actual filename implementation with a plain JDK and no Android dependencies. */
public final class NamingTemplateSelfTest {
    private static int checks;
    public static void main(String[] args) {
        Map<String, String> fields = NamingTemplate.fields("学习片段", "v123", "作者", "B站", 2);
        check("学习片段 [v123]".equals(NamingTemplate.preview(fields, NamingTemplate.DEFAULT)), "default filename");
        check("B站-作者-2".equals(NamingTemplate.preview(fields, "{platform}-{uploader}-{part}")), "only explicit metadata tokens");
        fields = NamingTemplate.fields("100% %(title)s ../a\\b\u0000?", "v", "", "", 0);
        String preview = NamingTemplate.preview(fields, "{title}");
        check(!preview.contains("/") && !preview.contains("\\") && !preview.contains("\u0000"), "metadata cannot escape the task directory");
        String output = NamingTemplate.outputPattern(fields, "{title}");
        check(output.startsWith("100%% %%(title)s"), "metadata percent expressions are literal");
        check(output.endsWith(".%(ext)s"), "only the trusted extension is an engine expression");
        check(output.equals(NamingTemplate.patternForName(preview)), "preview and actual pattern use identical rendered metadata");
        String longTitle = "龟😀".repeat(100);
        String shortened = NamingTemplate.preview(NamingTemplate.fields(longTitle, "id", "", "", 0), "{title}");
        check(shortened.getBytes(StandardCharsets.UTF_8).length <= NamingTemplate.MAX_NAME_BYTES, "UTF-8 length bound");
        check(!Character.isHighSurrogate(shortened.charAt(shortened.length()-1)), "no cut surrogate pairs");
        check(shortened.equals(new String(shortened.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)), "UTF-8 round trip");
        check("_CON".equals(NamingTemplate.preview(NamingTemplate.fields("CON", "", "", "", 0), "{title}")), "portable reserved filename");
        check("未命名".equals(NamingTemplate.preview(NamingTemplate.fields("../", "", "", "", 0), "{uploader}")), "missing values get safe fallback");
        check("abc".equals(NamingTemplate.preview(NamingTemplate.fields("...abc. ", "", "", "", 0), "{title}")), "dots cannot become a parent directory");
        for (String invalid : new String[]{"", " ", "{unknown}", "{title", "title}", "../{title}", "a\\{title}", "x\ny", ".", ".."}) {
            try { NamingTemplate.validate(invalid); throw new AssertionError("accepted invalid template " + invalid); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        check("100%".equals(NamingTemplate.preview(Map.of(), "100%")), "literal percent remains readable in previews");
        check("100%%.%(ext)s".equals(NamingTemplate.outputPattern(Map.of(), "100%")), "literal percent is safe in output patterns");
        System.out.println("NamingTemplateSelfTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
