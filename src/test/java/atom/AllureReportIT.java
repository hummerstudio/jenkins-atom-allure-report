package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The allure atom, started the way the runner starts it.
 *
 * <p>What {@code tools/smoke.sh} checked: the per-status counts, the failing test being named on the page, the
 * structured {@code report} field the runner archives, the {@code failOnFailure} gate, a missing results directory,
 * and a truncated result file — which must fail by default rather than vanish from the report in silence.
 */
class AllureReportIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** One {@code *-result.json} as allure writes it. */
    private static String result(String name, String status, long start, long stop, String feature, String message) {
        String labels = feature.isEmpty() ? "[]" : "[{\"name\": \"feature\", \"value\": \"" + feature + "\"}]";
        String details = message.isEmpty() ? "{}" : "{\"message\": \"" + message + "\"}";
        return "{\"uuid\": \"" + name + "\", \"name\": \"" + name.replace('-', ' ') + "\", \"status\": \"" + status
                + "\", \"start\": " + start + ", \"stop\": " + stop + ", \"labels\": " + labels
                + ", \"statusDetails\": " + details + "}";
    }

    private Atom fixture() {
        return atom.file("allure-results/login-result.json", result("login", "passed", 1000, 1600, "auth", ""))
                .file("allure-results/logout-result.json", result("logout", "passed", 1700, 1800, "auth", ""))
                .file("allure-results/checkout-result.json",
                        result("checkout", "failed", 2000, 5000, "pay", "expected 200, got 500"))
                .file("allure-results/flaky-result.json", result("flaky", "broken", 5100, 5200, "pay", "socket closed"))
                .file("allure-results/skipped-test-result.json", result("skipped-test", "skipped", 0, 0, "pay", ""));
    }

    @Test
    @DisplayName("counts, the failing test named on the page, and a report field the runner can archive")
    void countsAndRenders() throws Exception {
        AtomResult run = fixture().input("{}").run();

        run.assertOk();
        assertEquals("5", run.field("total"));
        assertEquals("2", run.field("passed"));
        assertEquals("1", run.field("failed"));
        assertEquals("1", run.field("broken"));
        assertEquals("1", run.field("skipped"));

        String page = run.file("out/allure/index.html");
        assertTrue(page != null, "no report page");
        assertTrue(page.contains("expected 200, got 500"), "the failure message is not in the report");
        assertTrue(page.contains("checkout"), "the failing test is not named");
        assertTrue(page.contains("class=\"card\""), "the report has no cards");

        Map<?, ?> report = (Map<?, ?>) ((Map<?, ?>) run.document().get("data")).get("report");
        assertEquals("report", report.get("type"), String.valueOf(report));
        assertTrue(String.valueOf(report.get("path")).endsWith("/out/allure"),
                "the report path must point at the report directory: " + report);
        assertEquals("index.html", report.get("target"), String.valueOf(report));
    }

    @Test
    @DisplayName("failOnFailure fails the step when a test did not pass, and says how many")
    void gateFails() throws Exception {
        AtomResult run = fixture().input("failOnFailure", "true").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("2 test(s) did not pass"),
                "gate message:\n" + run.document());
    }

    @Test
    @DisplayName("an all-green run passes the same gate")
    void gatePassesWhenAllGreen() throws Exception {
        AtomResult run = atom.file("allure-results/a-result.json", result("a", "passed", 0, 10, "", ""))
                .file("allure-results/b-result.json", result("b", "passed", 0, 10, "", ""))
                .input("failOnFailure", "true")
                .run();

        run.assertOk();
        assertEquals("2", run.field("passed"));
    }

    @Test
    @DisplayName("a missing results directory is refused")
    void refusesMissingResults() throws Exception {
        AtomResult run = atom.input("{}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no allure results at"),
                "message:\n" + run.document());
    }

    @Test
    @DisplayName("a truncated result file fails by default, and allowUnreadableReports lets it through")
    void truncatedResultFile() throws Exception {
        Atom truncated = atom.file("allure-results/ok-result.json",
                        "{\"uuid\":\"x\",\"name\":\"t\",\"status\":\"passed\",\"stage\":\"s\",\"start\":1000,"
                                + "\"stop\":1100,\"labels\":[],\"statusDetails\":{}}")
                .file("allure-results/half-result.json", "{\"uuid\": \"half\", \"name\": \"trunc")
                .input("{}");

        AtomResult failed = truncated.run();
        failed.assertRefused();
        assertTrue(failed.file("output.json").contains("\"errorCode\":40003"),
                "expected errorCode 40003:\n" + failed.document());
        assertTrue(failed.file("output.json").contains("half-result.json"),
                "the failure must name the file:\n" + failed.document());

        AtomResult allowed = atom.file("allure-results/ok-result.json",
                        "{\"uuid\":\"x\",\"name\":\"t\",\"status\":\"passed\",\"stage\":\"s\",\"start\":1000,"
                                + "\"stop\":1100,\"labels\":[],\"statusDetails\":{}}")
                .file("allure-results/half-result.json", "{\"uuid\": \"half\", \"name\": \"trunc")
                .input("allowUnreadableReports", "true")
                .run();

        allowed.assertOk();
        assertEquals("1", allowed.field("total"), "only the readable result is counted");
    }
}
