package com.mfb.lending.module.auth.security;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.UnauthorizedException;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

public class JwtService {

    public static final String PURPOSE_ACCESS = "ACCESS";
    public static final String PURPOSE_MFA = "MFA";

    private final AuthProperties properties;
    private final Clock clock;
    private final byte[] secret;

    public JwtService(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.secret = properties.hmacSecretBytes();
    }

    public String accessToken(
            UUID userId,
            UUID sessionId,
            PrincipalType principalType,
            List<String> roles,
            List<String> permissions,
            List<UUID> branchIds,
            boolean unrestrictedBranches) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getJwt().getAccessTokenTtl());
        JWTClaimsSet claims = baseClaims(userId, PURPOSE_ACCESS, now, expiresAt)
                .claim("sid", sessionId.toString())
                .claim("ptyp", principalType.name())
                .claim("roles", roles)
                .claim("perms", permissions)
                .claim("branches", branchIds.stream().map(UUID::toString).toList())
                .claim("unrestricted", unrestrictedBranches)
                .build();
        return sign(claims);
    }

    public String mfaToken(UUID userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getJwt().getMfaTokenTtl());
        return sign(baseClaims(userId, PURPOSE_MFA, now, expiresAt).build());
    }

    public AccessClaims parseAccessToken(String token) {
        JWTClaimsSet claims = verifiedClaims(token, PURPOSE_ACCESS);
        try {
            return new AccessClaims(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.getStringClaim("sid")),
                    PrincipalType.valueOf(claims.getStringClaim("ptyp")));
        } catch (RuntimeException | ParseException ex) {
            throw invalid();
        }
    }

    public UUID parseMfaToken(String token) {
        JWTClaimsSet claims = verifiedClaims(token, PURPOSE_MFA);
        try {
            return UUID.fromString(claims.getSubject());
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private JWTClaimsSet.Builder baseClaims(UUID userId, String purpose, Instant now, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .issuer(properties.getJwt().getIssuer())
                .audience(properties.getJwt().getAudience())
                .subject(userId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("purpose", purpose);
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID("hmac-1").build(),
                    claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Access token could not be signed", ex);
        }
    }

    private JWTClaimsSet verifiedClaims(String token, String expectedPurpose) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secret))) {
                throw invalid();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.getJwt().getIssuer().equals(claims.getIssuer())
                    || claims.getAudience() == null
                    || !claims.getAudience().contains(properties.getJwt().getAudience())) {
                throw invalid();
            }
            if (!expectedPurpose.equals(claims.getStringClaim("purpose"))) {
                throw invalid();
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null) {
                throw invalid();
            }
            if (expiration.toInstant().plusSeconds(30).isBefore(clock.instant())) {
                throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED, "The access token has expired");
            }
            return claims;
        } catch (UnauthorizedException ex) {
            throw ex;
        } catch (ParseException | JOSEException | RuntimeException ex) {
            throw invalid();
        }
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException(ErrorCode.UNAUTHORIZED, "The access token is not valid");
    }

    public record AccessClaims(UUID userId, UUID sessionId, PrincipalType principalType) {
    }
}
