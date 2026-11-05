package com.jenkinschina.atom.allure;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.Glob;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turn {@code allure-results} into a report without Allure itself.
 *
 * <p>The Allure plugin shells out to the Allure CLI; the interesting half of that — the numbers and the failing
 * tests — is just JSON. This reads {@code *-result.json} files, counts what happened, and writes one self-contained
 * HTML page, so it works on an agent with no JVM toolchain beyond the one running this atom.
 */
public class AllureAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String resultsDir = context.getParam("resultsDir", "allure-results");
        String reportDir = context.getParam("reportDir", "out/allure");
        String title = context.getParam("title", "Allure report");
        boolean failOnFailure = context.getBooleanParam("failOnFailure", false);

        Path results = workspace.resolve(resultsDir).normalize();
        if (!results.startsWith(workspace) || !Files.isDirectory(results)) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "no allure results at " + resultsDir + " (the directory must exist and hold *-result.json)");
        }

        List<Map<?, ?>> results_ = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (Path file : Glob.find(workspace, resultsDir.replaceAll("/+$", "") + "/*-result.json")) {
            try {
                if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> result) {
                    results_.add(result);
                } else {
                    unreadable.add(file.getFileName() + ": not a JSON object");
                }
            } catch (RuntimeException e) {
                // a truncated *-result.json used to disappear from the report in silence, which reads as
                // "that test never ran" rather than "the results directory is damaged"
                unreadable.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        boolean allowUnreadable = context.getBooleanParam("allowUnreadableReports", false);
        if (!unreadable.isEmpty() && !allowUnreadable) {
            return AtomResult.error(ErrorType.USER, 40003,
                    unreadable.size() + " allure result file(s) could not be parsed: " + unreadable.get(0)
                            + " (set allowUnreadableReports=true to accept this)");
        }
        if (results_.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "no *-result.json under " + resultsDir);
        }

        int passed = 0;
        int failed = 0;
        int broken = 0;
        int skipped = 0;
        long durationMs = 0;
        List<String> failures = new ArrayList<>();
        List<Test> tests = new ArrayList<>();
        for (Map<?, ?> result : results_) {
            String status = text(result, "status", "unknown").toLowerCase(Locale.ROOT);
            String name = text(result, "name", "(unnamed)");
            String feature = label(result, "feature");
            long start = number(result.get("start"));
            long stop = number(result.get("stop"));
            long duration = Math.max(0, stop - start);
            durationMs += duration;
            switch (status) {
                case "passed" -> passed++;
                case "failed" -> {
                    failed++;
                    failures.add(name + (feature.isEmpty() ? "" : " [" + feature + "]") + ": " + message(result));
                }
                case "broken" -> {
                    broken++;
                    failures.add(name + " (broken): " + message(result));
                }
                case "skipped" -> skipped++;
                default -> {
                }
            }
            tests.add(new Test(name, feature, status, duration));
        }
        int total = results_.size();
        tests.sort(Comparator.comparingLong(Test::duration).reversed());

        Path outDir = workspace.resolve(reportDir).normalize();
        if (!outDir.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40003, "reportDir must stay inside the workspace");
        }
        Files.createDirectories(outDir);
        String html = render(title, total, passed, failed, broken, skipped, durationMs, tests, failures);
        Files.writeString(outDir.resolve("index.html"), html, StandardCharsets.UTF_8);

        context.log("[allure] " + total + " tests: " + passed + " passed, " + failed + " failed, "
                + broken + " broken, " + skipped + " skipped");

        AtomResult result = AtomResult.success()
                .data("total", String.valueOf(total))
                .data("passed", String.valueOf(passed))
                .data("failed", String.valueOf(failed))
                .data("broken", String.valueOf(broken))
                .data("skipped", String.valueOf(skipped))
                .data("durationMs", String.valueOf(durationMs))
                // .report() writes the structured field the runner archives; a plain string is just text
                .report("report", title, outDir.toString(), "index.html");
        if (failOnFailure && (failed + broken) > 0) {
            AtomResult error = AtomResult.error(ErrorType.USER, 40004,
                    (failed + broken) + " test(s) did not pass: " + String.join("; ", limit(failures, 5)));
            return error
                    .data("total", String.valueOf(total))
                    .data("passed", String.valueOf(passed))
                    .data("failed", String.valueOf(failed))
                    .data("broken", String.valueOf(broken))
                    .data("skipped", String.valueOf(skipped))
                    .report("report", title, outDir.toString(), "index.html");
        }
        return result;
    }

    private static String label(Map<?, ?> result, String wanted) {
        if (result.get("labels") instanceof List<?> labels) {
            for (Object item : labels) {
                if (item instanceof Map<?, ?> label && wanted.equals(String.valueOf(label.get("name")))) {
                    return String.valueOf(label.get("value"));
                }
            }
        }
        return "";
    }

    private static String message(Map<?, ?> result) {
        if (result.get("statusDetails") instanceof Map<?, ?> details) {
            String message = text(details, "message", "");
            if (!message.isBlank()) {
                return message.lines().findFirst().orElse(message);
            }
        }
        return "(no message)";
    }

    private static String text(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> limit(List<String> values, int count) {
        return values.size() > count ? values.subList(0, count) : values;
    }

    private static String render(String title, int total, int passed, int failed, int broken, int skipped,
                                 long durationMs, List<Test> tests, List<String> failures) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>")
                .append(escape(title)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9;color:#1f2328}")
                .append("h1{font-size:22px;margin:0 0 6px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:22px}")
                .append(".card{background:#fff;border:1px solid #e6e8eb;border-radius:12px;padding:12px 18px;min-width:96px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("table{border-collapse:collapse;width:100%;background:#fff;border:1px solid #e6e8eb;border-radius:12px;overflow:hidden}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f2;font-size:13px}")
                .append("th{background:#fafbfc;color:#57606a;font-weight:600}")
                .append(".failed{color:#b42318;font-weight:600}.passed{color:#1a7f37}.broken{color:#b54708}.skipped{color:#6b7280}")
                .append("ul.failures{background:#fff;border:1px solid #e6e8eb;border-radius:12px;padding:14px 30px}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(title)).append("</h1>");
        html.append("<p class=\"meta\">").append(total).append(" tests · ")
                .append(String.format(Locale.ROOT, "%.1f s", durationMs / 1000.0)).append("</p>");
        html.append("<div class=\"cards\">");
        card(html, "passed", passed);
        card(html, "failed", failed);
        card(html, "broken", broken);
        card(html, "skipped", skipped);
        html.append("</div>");

        if (!failures.isEmpty()) {
            html.append("<h2>Not passing</h2><ul class=\"failures\">");
            for (String failure : failures) {
                html.append("<li>").append(escape(failure)).append("</li>");
            }
            html.append("</ul>");
        }

        html.append("<h2>Tests</h2><table><tr><th>Test</th><th>Feature</th><th>Status</th><th>Duration</th></tr>");
        for (Test test : tests) {
            html.append("<tr><td>").append(escape(test.name())).append("</td><td>")
                    .append(escape(test.feature())).append("</td><td class=\"").append(test.status()).append("\">")
                    .append(escape(test.status())).append("</td><td>")
                    .append(String.format(Locale.ROOT, "%.3f s", test.duration() / 1000.0)).append("</td></tr>");
        }
        html.append("</table></body></html>");
        return html.toString();
    }

    private static void card(StringBuilder html, String label, int value) {
        html.append("<div class=\"card\"><b class=\"").append(label).append("\">").append(value)
                .append("</b><span>").append(label).append("</span></div>");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Test(String name, String feature, String status, long duration) {}
}
