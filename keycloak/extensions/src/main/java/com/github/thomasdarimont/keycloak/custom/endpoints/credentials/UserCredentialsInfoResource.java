package com.github.thomasdarimont.keycloak.custom.endpoints.credentials;

import com.github.thomasdarimont.keycloak.custom.auth.backupcodes.credentials.BackupCodeCredentialModel;
import com.github.thomasdarimont.keycloak.custom.auth.mfa.sms.credentials.SmsCredentialModel;
import lombok.Data;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.AccountRoles;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.resources.Cors;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;

public class UserCredentialsInfoResource {

    private static final Set<String> RELEVANT_CREDENTIAL_TYPES = Set.of(
            PasswordCredentialModel.TYPE,
            SmsCredentialModel.TYPE,
            OTPCredentialModel.TYPE,
            BackupCodeCredentialModel.TYPE);

    private static final Set<String> REMOVABLE_CREDENTIAL_TYPES = Set.of(
            SmsCredentialModel.TYPE,
            OTPCredentialModel.TYPE,
            BackupCodeCredentialModel.TYPE);

    private final KeycloakSession session;
    private final AccessToken token;

    @Context
    private HttpRequest request;

    public UserCredentialsInfoResource(KeycloakSession session, AccessToken token) {
        this.session = session;
        this.token = token;
    }

    @OPTIONS
    public Response getCorsOptions() {
        return withCors(request, Response.ok()).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response readCredentialInfo() {

        if (token == null) {
            return Response.status(UNAUTHORIZED).build();
        }

        KeycloakContext context = session.getContext();
        RealmModel realm = context.getRealm();
        UserModel user = session.users().getUserById(realm, token.getSubject());
        if (user == null) {
            return Response.status(UNAUTHORIZED).build();
        }

        var resourceAccess = token.getResourceAccess();
        AccessToken.Access accountAccess = resourceAccess == null ? null : resourceAccess.get(Constants.ACCOUNT_MANAGEMENT_CLIENT_ID);
        var canAccessAccount = accountAccess != null
                && (accountAccess.isUserInRole(AccountRoles.MANAGE_ACCOUNT) || accountAccess.isUserInRole(AccountRoles.VIEW_PROFILE));
        if (canAccessAccount) {
            return Response.status(FORBIDDEN).build();
        }

        var credentialInfos = loadCredentialInfosForUser(realm, user);

        var responseBody = new HashMap<String, Object>();
        responseBody.put("credentialInfos", credentialInfos);

        return withCors(request, Response.ok(responseBody)).build();
    }

    @DELETE
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeCredentialByType(RemoveCredentialRequest removeCredentialRequest) {

        if (token == null) {
            return Response.status(UNAUTHORIZED).build();
        }

        KeycloakContext context = session.getContext();
        RealmModel realm = context.getRealm();
        UserModel user = session.users().getUserById(realm, token.getSubject());
        if (user == null) {
            return Response.status(UNAUTHORIZED).build();
        }

        var resourceAccess = token.getResourceAccess();
        AccessToken.Access accountAccess = resourceAccess == null ? null : resourceAccess.get(Constants.ACCOUNT_MANAGEMENT_CLIENT_ID);
        var canAccessAccount = accountAccess != null
                && (accountAccess.isUserInRole(AccountRoles.MANAGE_ACCOUNT) || accountAccess.isUserInRole(AccountRoles.VIEW_PROFILE));
        if (canAccessAccount) {
            return Response.status(FORBIDDEN).build();
        }

        String credentialType = removeCredentialRequest.getCredentialType();
        if (!REMOVABLE_CREDENTIAL_TYPES.contains(credentialType)) {
            return Response.status(BAD_REQUEST).build();
        }

        String credentialId = removeCredentialRequest.getCredentialId();
        // TODO check token.getAuth_time()

        UserCredentialManager ucm = session.userCredentialManager();
        var credentials = ucm.getStoredCredentialsByTypeStream(realm, user, credentialType)
                .collect(Collectors.toList());
        if (credentials.isEmpty()) {
            return withCors(request, Response.status(Response.Status.NOT_FOUND)).build();
        }

        int removedCredentialCount = 0;
        for (var credential : credentials) {
            if (credentialId != null && !credential.getId().equals(credentialId)) {
                continue;
            }
            if (removeCredentialForUser(realm, user, credential)) {
                removedCredentialCount++;
            }
        }

        var responseBody = new HashMap<String, Object>();
        responseBody.put("removedCredentialCount", removedCredentialCount);

        return withCors(request, Response.ok(responseBody)).build();
    }

    private boolean removeCredentialForUser(RealmModel realm, UserModel user, CredentialModel credentialModel) {
        return session.userCredentialManager().removeStoredCredential(realm, user, credentialModel.getId());
    }

    private Map<String, List<CredentialInfo>> loadCredentialInfosForUser(RealmModel realm, UserModel user) {

        var ucm = session.userCredentialManager();
        var credentials = ucm.getStoredCredentialsStream(realm, user).collect(Collectors.toList());

        var credentialData = new HashMap<String, List<CredentialInfo>>();
        for (var credential : credentials) {
            String type = credential.getType();
            if (!RELEVANT_CREDENTIAL_TYPES.contains(type)) {
                continue;
            }
            String userLabel = credential.getUserLabel();
            if (userLabel == null) {
                userLabel = type;
            }
            credentialData.computeIfAbsent(type, s -> new ArrayList<>()).add(
                    new CredentialInfo(credential.getId(), type, userLabel, credential.getCreatedDate()));
        }

        var credentialInfoData = new HashMap<String, List<CredentialInfo>>();
        for (var credentialType : credentialData.keySet()) {
            var creds = credentialData.get(credentialType);
            if (creds.size() > 1) {
                if (shouldAggregate(credentialType)) {
                    CredentialInfo firstCredential = creds.get(0);
                    CredentialInfo aggregatedCred = new CredentialInfo(null, credentialType, credentialType + " [" + creds.size() + "]", firstCredential.getCreatedAt());
                    aggregatedCred.setCollection(true);
                    aggregatedCred.setCount(creds.size());
                    credentialInfoData.put(credentialType, List.of(aggregatedCred));
                } else {
                    credentialInfoData.put(credentialType, creds);
                }
            } else {
                credentialInfoData.put(credentialType, creds);
            }
        }

        return credentialInfoData;
    }

    private boolean shouldAggregate(String credentialType) {
        return BackupCodeCredentialModel.TYPE.equals(credentialType);
    }

    private Cors withCors(HttpRequest request, Response.ResponseBuilder responseBuilder) {

        URI baseUri = URI.create(request.getHttpHeaders().getHeaderString("origin"));
        String origin = baseUri.getHost();
        boolean trustedDomain = origin.endsWith(".acme.test");

        Cors cors = Cors.add(request, responseBuilder);
        if (trustedDomain) {
            cors.allowedOrigins(baseUri.getScheme() + "://" + origin + ":" + baseUri.getPort()); //
        }

        return cors.auth().allowedMethods("GET", "DELETE", "OPTIONS").preflight();
    }

    @Data
    public static class CredentialInfo {

        private final String credentialId;
        private final String credentialType;
        private final String credentialLabel;
        private final Long createdAt;

        private boolean collection;
        private int count;

        private Map<String, Object> metadata;
    }

    @Data
    public static class RemoveCredentialRequest {
        String credentialType;
        String credentialId;
    }
}
