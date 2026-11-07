package dev.ledgerlab.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/** AC-13.3: the Alertmanager configuration is valid and routes by severity, judged by {@code amtool} itself. */
class AlertmanagerConfigTest {
    private static final String CONFIG = "--config.file=" + Amtool.REAL_CONFIG;

    @Test
    void ac_13_3_theConfigurationPassesAmtoolCheck() {
        Amtool.Result result = Amtool.run("check-config", Amtool.REAL_CONFIG);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("SUCCESS").contains("1 receivers");
    }

    @Test
    void ac_13_3_pageAndTicketAlertsTakeTheirOwnSubRoute() {
        // Both sub-routes deliver to the one receiver, so the receiver name cannot tell them apart: the matched path
        // that `--tree` prints does. A severity nobody routes falls back to the default route.
        String page = routeTest("severity=page");
        assertThat(page).contains("{severity=\"page\"}  receiver: local-sink").doesNotContain("severity=\"ticket\"");
        String ticket = routeTest("severity=ticket");
        assertThat(ticket)
                .contains("{severity=\"ticket\"}  receiver: local-sink")
                .doesNotContain("severity=\"page\"");
        String other = routeTest("severity=info");
        assertThat(other).contains("default-route  receiver: local-sink").doesNotContain("severity=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac_13_3_thePageRoutePagesFasterThanTheTicketRoute() throws IOException {
        // amtool does not print the timing parameters, so they are read from the file the checks above validated.
        Map<String, Object> root =
                new Yaml().load(Files.readString(Amtool.OPS_DIR.resolve("alertmanager.yml"), StandardCharsets.UTF_8));
        Map<String, Object> route = (Map<String, Object>) root.get("route");
        assertThat(route.get("group_by")).isEqualTo(List.of("alertname", "kind"));
        Map<String, Map<String, Object>> bySeverity = new LinkedHashMap<>();
        for (Map<String, Object> sub : (List<Map<String, Object>>) route.get("routes"))
            bySeverity.put(((List<String>) sub.get("matchers")).get(0), sub);
        assertThat(bySeverity).containsOnlyKeys("severity=\"page\"", "severity=\"ticket\"");
        assertThat(bySeverity.get("severity=\"page\""))
                .containsEntry("group_wait", "30s")
                .containsEntry("group_interval", "5m")
                .containsEntry("repeat_interval", "1h");
        assertThat(bySeverity.get("severity=\"ticket\""))
                .containsEntry("group_wait", "2m")
                .containsEntry("group_interval", "10m")
                .containsEntry("repeat_interval", "12h");
    }

    private static String routeTest(String severity) {
        Amtool.Result result = Amtool.run("config", "routes", "test", "--tree", CONFIG, severity, "alertname=X");
        assertThat(result.exitCode()).as(result.output()).isZero();
        return result.output();
    }

    @Test
    void ac_13_3_thePinnedVersionAcceptsUrlFileForAFutureRealChannel(@TempDir Path dir) throws IOException {
        // Design 2.1: the address of a real channel goes through `url_file` (a file outside the repository). This
        // only proves the pinned version understands the field; nothing here is sent anywhere.
        String withUrlFile = Files.readString(Amtool.OPS_DIR.resolve("alertmanager.yml"), StandardCharsets.UTF_8)
                .replace("url: http://host.docker.internal:9095/alerts", "url_file: /run/secrets/alert-channel-url");
        assertThat(withUrlFile).contains("url_file:");
        Files.writeString(dir.resolve("url-file.yml"), withUrlFile, StandardCharsets.UTF_8);
        Amtool.Result result = Amtool.run(dir, "check-config", Amtool.generated("url-file.yml"));
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    @Test
    void ac_13_3_aBrokenConfigurationMakesAmtoolFail(@TempDir Path dir) throws IOException {
        // A route that names a receiver nobody defines: the mirror image of the passing check above.
        String broken = Files.readString(Amtool.OPS_DIR.resolve("alertmanager.yml"), StandardCharsets.UTF_8)
                .replace("receiver: local-sink", "receiver: nobody");
        Files.writeString(dir.resolve("broken.yml"), broken, StandardCharsets.UTF_8);
        Amtool.Result result = Amtool.run(dir, "check-config", Amtool.generated("broken.yml"));
        assertThat(result.exitCode()).as(result.output()).isNotZero();
    }
}
