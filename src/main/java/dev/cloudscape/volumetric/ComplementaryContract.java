package dev.cloudscape.volumetric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative source-interface checks, independent of the archive name/version. */
public final class ComplementaryContract {
    public static final Map<String, String> TARGETS = Map.of(
        "unboundClouds.glsl", "/shaders/lib/atmospherics/clouds/unboundClouds.glsl",
        "reimaginedClouds.glsl", "/shaders/lib/atmospherics/clouds/reimaginedClouds.glsl",
        "cloudShadows.glsl", "/shaders/lib/lighting/cloudShadows.glsl",
        "mainClouds.glsl", "/shaders/lib/atmospherics/clouds/mainClouds.glsl");
    private static final String LIB = "/shaders/lib/";
    private static final Pattern LEXER = Pattern.compile(
        "/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|\\r\\n|[\\r\\n]|[ \\t\\f]+|"
        + "[A-Za-z_][A-Za-z_0-9]*|(?:[0-9]+\\.[0-9]*|\\.[0-9]+|[0-9]+)(?:[eE][+-]?[0-9]+)?[uUfF]?|"
        + "<<=|>>=|\\+\\+|--|&&|\\|\\||==|!=|<=|>=|<<|>>|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|.");
    private static final Set<String> TYPES = Set.of("void", "bool", "int", "float", "vec2", "vec3", "vec4",
        "ivec2", "ivec3", "ivec4", "mat3", "mat4", "sampler2D");
    private static final List<Rule> RULES = rules();

    public record Token(String text, int start, int end) {}
    private record Rule(String key, String file, String kind, String name) {}
    private record Code(List<Token> tokens, List<String> contexts, List<String> directives) {}
    public record Plan(Map<String, String> sources, String reason) {
        public boolean compatible() { return reason == null; }
    }

    private ComplementaryContract() {}

    // Separate optimization contract: a changed default-style mapping must
    // disable atlas gating without disabling the basic cloud adapter.
    static String atlasStyleFingerprint(String source) {
        return UnboundAdapter.sha256(extract(new Rule("atlas.default", "", "define", "CLOUD_STYLE_DEFAULT"), source)
            + "\n" + extract(new Rule("atlas.selection", "", "define", "CLOUD_STYLE"), source));
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("wrapper", TARGETS.get("mainClouds.glsl"), "whole", ""));
        for (String file : List.of("unboundClouds.glsl", "reimaginedClouds.glsl"))
            rules.add(new Rule(file + ".entry", TARGETS.get(file), "signature", "GetVolumetricClouds"));
        rules.add(new Rule("shadow.entry", TARGETS.get("cloudShadows.glsl"), "signature", "GetCloudShadow"));
        rules.add(new Rule("sky.entry", LIB + "atmospherics/sky.glsl", "signature", "GetSky"));
        rules.add(new Rule("math.pow2", LIB + "util/commonFunctions.glsl", "function", "pow2"));
        for (String name : List.of("noisetex", "depthtex0", "dhDepthTex", "rainFactor", "frameTimeCounter",
            "isEyeInWater", "gbufferModelViewInverse", "cameraPosition", "maxBlindnessDarkness"))
            rules.add(new Rule("uniform." + name, LIB + "uniforms.glsl", "type", name));
        for (String name : List.of("cloudAmbientColor", "cloudLightColor"))
            rules.add(new Rule("color." + name, LIB + "colors/cloudColors.glsl", "type", name));
        for (String name : List.of("renderDistance", "syncedTime", "cloudAlt1i", "cloudAlt2i", "texelCoord",
            "cameraPositionBestFract"))
            rules.add(new Rule("common." + name, LIB + "common.glsl", "declaration", name));
        for (String name : List.of("CLOUDS_REIMAGINED", "CLOUDS_UNBOUND", "CLOUD_SPEED_MULT"))
            rules.add(new Rule("define." + name, LIB + "common.glsl", "define", name));
        for (String include : List.of("/lib/uniforms.glsl", "/lib/util/commonFunctions.glsl"))
            rules.add(new Rule("include." + include, LIB + "common.glsl", "include", include));
        return List.copyOf(rules);
    }

    /** Comments are skipped, but token boundaries and preprocessor line endings are retained. */
    public static List<Token> tokens(String source) {
        List<Token> tokens = new ArrayList<>();
        var matcher = LEXER.matcher(source);
        boolean directive = false;
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("/*") || token.startsWith("//")) continue;
            if (token.equals("\r\n") || token.equals("\r") || token.equals("\n")) {
                if (directive) {
                    if (!tokens.isEmpty() && tokens.getLast().text().equals("\\")) tokens.removeLast();
                    else { tokens.add(new Token("\n", matcher.start(), matcher.end())); directive = false; }
                }
                continue;
            }
            if (token.isBlank()) continue;
            if (token.equals("#")) directive = true;
            tokens.add(new Token(token, matcher.start(), matcher.end()));
        }
        if (directive) tokens.add(new Token("\n", source.length(), source.length()));
        return List.copyOf(tokens);
    }

    private static String join(List<Token> tokens) {
        return String.join(" ", tokens.stream().map(Token::text).toList());
    }

    private static Code parse(String source) {
        List<Token> raw = tokens(source), code = new ArrayList<>();
        List<String> contexts = new ArrayList<>(), directives = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Token token = raw.get(i);
            if (!token.text().equals("#")) {
                code.add(token); contexts.add(String.join(" / ", conditions)); continue;
            }
            int end = i;
            while (end < raw.size() && !raw.get(end).text().equals("\n")) end++;
            if (end <= i + 1) throw new IllegalArgumentException("Empty directive");
            String directive = join(raw.subList(i, end));
            String command = raw.get(i + 1).text();
            directives.add(String.join(" / ", conditions) + " : " + directive);
            switch (command) {
                case "if", "ifdef", "ifndef" -> conditions.add(directive);
                case "elif", "else" -> {
                    if (conditions.isEmpty()) throw new IllegalArgumentException("Unbalanced conditional");
                    conditions.set(conditions.size() - 1, conditions.getLast() + " -> " + directive);
                }
                case "endif" -> {
                    if (conditions.isEmpty()) throw new IllegalArgumentException("Unbalanced conditional");
                    conditions.removeLast();
                }
                default -> { }
            }
            i = end;
        }
        if (!conditions.isEmpty()) throw new IllegalArgumentException("Unclosed conditional");
        return new Code(code, contexts, directives);
    }

    private static int closing(List<Token> tokens, int start, String open, String close) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            if (tokens.get(i).text().equals(open)) depth++;
            if (tokens.get(i).text().equals(close) && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unbalanced " + open);
    }

    private static String signature(List<Token> header) {
        List<String> signature = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String token = header.get(i).text();
            signature.add(token);
            // Parameter names may change without changing the calling convention.
            if (i > 2 && TYPES.contains(token) && i + 1 < header.size()
                && header.get(i + 1).text().matches("[A-Za-z_][A-Za-z_0-9]*")) i++;
        }
        return String.join(" ", signature);
    }

    private static String extract(Rule rule, String source) {
        if (rule.kind.equals("whole")) return join(tokens(source));
        Code parsed = parse(source);
        if (rule.kind.equals("define") || rule.kind.equals("include")) {
            String needle = rule.kind.equals("include") ? "# include \"" + rule.name + "\""
                : "# define " + rule.name;
            List<String> selected = parsed.directives.stream().filter(d -> {
                String body = d.substring(d.indexOf(" : ") + 3);
                return body.equals(needle) || body.startsWith(needle + " ");
            }).toList();
            if (selected.isEmpty()) throw new IllegalArgumentException("Missing " + rule.key);
            // The numeric speed default is a setting, not a calling convention.
            if (rule.name.equals("CLOUD_SPEED_MULT"))
                return String.join("\n", selected.stream().map(d -> d.substring(0, d.indexOf(needle)) + needle).toList());
            return String.join("\n", selected);
        }
        List<String> found = new ArrayList<>();
        List<Token> code = parsed.tokens;
        for (int i = 1; i + 1 < code.size(); i++) {
            if (!code.get(i).text().equals(rule.name) || !TYPES.contains(code.get(i - 1).text())) continue;
            boolean function = code.get(i + 1).text().equals("(");
            if (function != (rule.kind.equals("signature") || rule.kind.equals("function"))) continue;
            int start = i - 1, end = i + 1;
            if (start > 0 && code.get(start - 1).text().equals("uniform")) start--;
            if (function) {
                end = closing(code, i + 1, "(", ")");
                if (end + 1 >= code.size() || !code.get(end + 1).text().equals("{")) continue;
                if (rule.kind.equals("function")) end = closing(code, end + 1, "{", "}");
                String header = rule.kind.equals("signature") ? signature(code.subList(start, end + 1))
                    : join(code.subList(start, end + 1));
                found.add(parsed.contexts.get(i) + " : " + header);
            } else {
                if (rule.kind.equals("declaration")) {
                    while (end < code.size() && !code.get(end).text().equals(";")) end++;
                    if (end == code.size()) throw new IllegalArgumentException("Unfinished " + rule.key);
                } else end = i;
                found.add(parsed.contexts.get(i) + " : " + join(code.subList(start, end + 1)));
            }
        }
        if (found.isEmpty()) throw new IllegalArgumentException("Missing " + rule.key);
        return String.join("\n", found);
    }

    public static Properties fingerprint(FileSystem fs) throws IOException {
        Properties result = new Properties();
        Map<String, String> files = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            String source = files.get(rule.file);
            if (source == null) {
                source = Files.readString(fs.getPath(rule.file), StandardCharsets.UTF_8);
                files.put(rule.file, source);
            }
            result.setProperty(rule.key, UnboundAdapter.sha256(extract(rule, source)));
        }
        return result;
    }

    public static Plan inspect(FileSystem fs) {
        try {
            Properties actual = fingerprint(fs), expected = new Properties();
            try (InputStream input = ComplementaryContract.class.getResourceAsStream("/complementary-interface.properties")) {
                if (input == null) return new Plan(Map.of(), "missing source-interface manifest");
                expected.load(input);
            }
            for (Rule rule : RULES) if (!actual.getProperty(rule.key).equals(expected.getProperty(rule.key)))
                return new Plan(Map.of(), "interface changed: " + rule.key);
            Map<String, String> originals = new LinkedHashMap<>();
            for (String file : TARGETS.values())
                originals.put(file, Files.readString(fs.getPath(file), StandardCharsets.UTF_8));
            return new Plan(Map.copyOf(originals), null);
        } catch (IOException | IllegalArgumentException error) {
            return new Plan(Map.of(), "source-interface check failed: " + error.getMessage());
        }
    }

    /** Locate one actual statement, ignoring comments/formatting; never patch a comment. */
    public static int statementEnd(String source, String statement) {
        List<Token> actual = tokens(source), marker = tokens(statement);
        int end = -1, matches = 0;
        for (int i = 0; i + marker.size() <= actual.size(); i++) {
            boolean same = true;
            for (int j = 0; j < marker.size(); j++)
                if (!actual.get(i + j).text().equals(marker.get(j).text())) { same = false; break; }
            if (same) { matches++; end = actual.get(i + marker.size() - 1).end(); }
        }
        if (matches != 1) throw new IllegalArgumentException("Expected one cloud endpoint statement");
        return end;
    }
}
