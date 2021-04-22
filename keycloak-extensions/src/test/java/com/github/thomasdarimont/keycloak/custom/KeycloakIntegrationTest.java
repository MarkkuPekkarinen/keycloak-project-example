package com.github.thomasdarimont.keycloak.custom;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.token.TokenService;
import org.keycloak.representations.AccessTokenResponse;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class KeycloakIntegrationTest {

    public static final String CUSTOM_REALM = "custom";

    public static final String TEST_CLIENT = "test-client";

    public static final String TEST_USER_PASSWORD = "test";

    public static final String REALM_IMPORT_FILE = "custom-realm.json";

    public static KeycloakContainer keycloak;

    static boolean keycloakLocal = false;

    @BeforeAll
    public static void beforeAll() {

        keycloak = KeycloakTestSupport.createKeycloakContainer(keycloakLocal, REALM_IMPORT_FILE);
        keycloak.withReuse(true);
        keycloak.start();
        keycloak.followOutput(new Slf4jLogConsumer(log));
    }

    @AfterAll
    public static void afterAll() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    @Test
    public void pingResourceShouldBeAccessibleForUser() {

        TokenService tokenService = KeycloakTestSupport.getTokenService(keycloak);

        AccessTokenResponse accessTokenResponse = tokenService.grantToken(CUSTOM_REALM, new Form()
                .param("grant_type", "password")
                .param("username", "tester")
                .param("password", TEST_USER_PASSWORD)
                .param("client_id", TEST_CLIENT)
                .param("scope", "openid custom.profile custom.api")
                .asMap());

        String accessToken = accessTokenResponse.getToken();
        System.out.println("Token: " + accessToken);

        CustomResources customResources = KeycloakTestSupport.getResteasyWebTarget(keycloak).proxy(CustomResources.class);
        Map<String, Object> response = customResources.ping(CUSTOM_REALM, "Bearer " + accessToken);
        System.out.println(response);

        assertThat(response).isNotNull();
        assertThat(response.get("user")).isEqualTo("tester");
    }


    interface CustomResources {

        @GET
        @Consumes(MediaType.APPLICATION_JSON)
        @javax.ws.rs.Path("/realms/{realm}/custom-resources/ping")
        Map<String, Object> ping(@PathParam("realm") String realm, @HeaderParam("Authorization") String token);
    }
}
