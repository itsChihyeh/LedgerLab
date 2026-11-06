package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AC-10.2: every route the investigation agent may call (agent/src/ledgerlab_agent/routes.json) is a GET handled by a
 * read-only query controller whose database access goes through the readonly data source only.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
class AgentRouteAllowlistTest {
    private static final Path ROUTES = Path.of("agent/src/ledgerlab_agent/routes.json");
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** The only handler classes an agent route may resolve to; widening this list is a design decision. */
    private static final Set<String> READ_ONLY_CONTROLLERS = Set.of(
            "dev.ledgerlab.investigation.internal.WorkbenchQueryController",
            "dev.ledgerlab.investigation.internal.PaymentQueryController",
            "dev.ledgerlab.investigation.internal.EventQueryController",
            "dev.ledgerlab.investigation.internal.RefundQueryController",
            "dev.ledgerlab.investigation.internal.CorrectionTraceController",
            "dev.ledgerlab.payouts.internal.PayoutQueryController");

    private static final List<String> WRITE_SEMANTICS =
            List.of("resolve", "corrections", "cancel", "seed", "actions", "query", "accept", "demo", "webhook");

    /** Example value per route parameter name; each must also satisfy the pattern declared in routes.json. */
    private static final Map<String, String> EXAMPLES = Map.ofEntries(
            Map.entry("issue_id", "iss_example1"),
            Map.entry("payment_ref", "pay_example1"),
            Map.entry("batch_id", "po_example1"),
            Map.entry("bank_txn_id", "bank_example1"),
            Map.entry("file_id", "file_example1"),
            Map.entry("seq", "1"),
            Map.entry("event_id", "evt_example1"),
            Map.entry("entry_id", "je_example1"),
            Map.entry("refund_id", "rfd_example1"),
            Map.entry("payout_id", "mpo_example1"),
            Map.entry("reference", "po_example1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @AfterAll
    static void close() {
        HTTP.close();
        DB.drop();
    }

    record Route(String tool, String method, String template, JsonNode params) {
        @Override
        public String toString() {
            return tool;
        }

        String examplePath() {
            String path = template;
            for (String name : params.propertyNames()) {
                if (params.path(name).path("in").asString().equals("path"))
                    path = path.replace("{" + name + "}", example(name));
            }
            return path;
        }

        String exampleUri() {
            var query = new ArrayList<String>();
            for (String name : params.propertyNames()) {
                JsonNode param = params.path(name);
                if (param.path("in").asString().equals("query")
                        && param.path("required").asBoolean())
                    query.add(name + "=" + URLEncoder.encode(example(name), StandardCharsets.UTF_8));
            }
            return examplePath() + (query.isEmpty() ? "" : "?" + String.join("&", query));
        }

        private String example(String name) {
            String value = EXAMPLES.get(name);
            assertThat(value)
                    .as("example value for parameter %s of %s", name, tool)
                    .isNotNull();
            assertThat(Pattern.compile(params.path(name).path("pattern").asString())
                            .matcher(value)
                            .find())
                    .as("example %s matches the pattern routes.json declares for %s", value, name)
                    .isTrue();
            return java.util.Objects.requireNonNull(value);
        }
    }

    static List<Route> routes() throws Exception {
        assertThat(ROUTES)
                .as("agent route table must exist (run from the repository root)")
                .exists();
        JsonNode table = new ObjectMapper().readTree(Files.readString(ROUTES, StandardCharsets.UTF_8));
        assertThat(table.isArray() && !table.isEmpty()).isTrue();
        var result = new ArrayList<Route>();
        for (JsonNode node : table)
            result.add(new Route(
                    node.path("tool").asString(),
                    node.path("method").asString(),
                    node.path("path_template").asString(),
                    node.path("params")));
        assertThat(result.stream().map(Route::tool)).doesNotHaveDuplicates();
        return result;
    }

    @ParameterizedTest
    @MethodSource("routes")
    void ac_10_2_routeIsAGetUnderApiButNotDemoAndHasNoWriteSemantics(Route route) {
        assertThat(route.method()).isEqualTo("GET");
        assertThat(route.template()).startsWith("/api/").doesNotStartWith("/api/demo");
        String lower = route.template().toLowerCase(java.util.Locale.ROOT);
        for (String fragment : WRITE_SEMANTICS)
            assertThat(lower).as(route.tool()).doesNotContain(fragment);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void ac_10_2_routeIsHandledByAReadOnlyQueryController(Route route) {
        var handlers = handlersFor(route);
        assertThat(handlers)
                .as("exactly one GET handler for %s", route.template())
                .hasSize(1);
        assertThat(handlers.getFirst().getBeanType().getName()).isIn(READ_ONLY_CONTROLLERS);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void ac_10_2_noWriteMappingSharesTheRoutePath(Route route) {
        String same = shape(route.template());
        var concrete = org.springframework.http.server.PathContainer.parsePath(route.examplePath());
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            var patterns = patterns(info);
            boolean overlaps = patterns.stream()
                    .anyMatch(p ->
                            p.matches(concrete) || shape(p.getPatternString()).equals(same));
            if (!overlaps) continue;
            Set<HttpMethod> methods = new HashSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(HttpMethod.valueOf(m.name())));
            assertThat(methods)
                    .as("%s on %s overlaps %s", entry.getValue(), info, route.template())
                    .containsExactly(HttpMethod.GET);
        }
    }

    @ParameterizedTest
    @MethodSource("routes")
    void ac_10_2_handlerReachesOnlyTheReadonlyDataSource(Route route) {
        var factory = (ConfigurableListableBeanFactory) context.getAutowireCapableBeanFactory();
        Class<?> type = handlersFor(route).getFirst().getBeanType();
        String beanName = context.getBeanNamesForType(type)[0];
        Set<String> chain = new java.util.LinkedHashSet<>();
        collectDependencies(factory, beanName, chain);
        Set<String> dataSources = new HashSet<>(List.of(context.getBeanNamesForType(DataSource.class)));
        assertThat(dataSources).contains("investigationDataSource").hasSizeGreaterThan(1);
        assertThat(chain).as("%s dependency chain", beanName).contains("investigationDataSource");
        dataSources.remove("investigationDataSource");
        assertThat(chain).as("%s dependency chain", beanName).doesNotContainAnyElementsOf(dataSources);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void ac_10_2_routeAnswersWithoutServerErrorOnAnEmptyDatabase(Route route) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + route.exampleUri()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s: %s", route.exampleUri(), response.body())
                .isIn(200, 404);
    }

    @Test
    void ac_10_2_everyRouteParameterHasAnExample() throws Exception {
        for (Route route : routes()) route.exampleUri();
    }

    private List<HandlerMethod> handlersFor(Route route) {
        String shape = shape(route.template());
        var found = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            boolean samePath = patterns(info).stream()
                    .anyMatch(p -> shape(p.getPatternString()).equals(shape));
            boolean get = info.getMethodsCondition().getMethods().stream()
                    .anyMatch(m -> m.name().equals("GET"));
            if (samePath && get) found.put(info, handler);
        });
        return List.copyOf(found.values());
    }

    private static Set<PathPattern> patterns(RequestMappingInfo info) {
        return java.util.Objects.requireNonNull(info.getPathPatternsCondition(), "path pattern parsing is enabled")
                .getPatterns();
    }

    /** A path with its variable names erased, so /api/x/{issue_id} and /api/x/{id} compare equal. */
    private static String shape(String path) {
        return path.replaceAll("\\{[^}/]+}", "{}");
    }

    private static void collectDependencies(ConfigurableListableBeanFactory factory, String bean, Set<String> seen) {
        for (String dependency : factory.getDependenciesForBean(bean)) {
            if (seen.add(dependency)) collectDependencies(factory, dependency, seen);
        }
    }
}
