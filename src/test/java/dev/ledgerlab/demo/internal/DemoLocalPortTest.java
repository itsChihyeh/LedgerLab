package dev.ledgerlab.demo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;

/** AC-12.4: the Actuator server on its own port must not become the address the demo calls itself on. */
class DemoLocalPortTest {

    @Test
    void ac_12_4_theManagementServersPortDoesNotReplaceTheApplicationPort() {
        var listener = new DemoConfiguration.LocalPort();

        listener.onApplicationEvent(started("server", 8080));
        listener.onApplicationEvent(started("management", 8079));

        assertThat(listener.port()).isEqualTo(8080);
    }

    private static WebServerInitializedEvent started(String namespace, int port) {
        var server = mock(WebServer.class);
        when(server.getPort()).thenReturn(port);
        var context = mock(WebServerApplicationContext.class);
        when(context.getServerNamespace()).thenReturn(namespace);
        var event = mock(WebServerInitializedEvent.class);
        when(event.getWebServer()).thenReturn(server);
        when(event.getApplicationContext()).thenReturn(context);
        return event;
    }
}
