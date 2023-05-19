/*-
 * ---license-start
 * WHO Digital Documentation Covid Certificate Gateway Service / ddcc-gateway
 * ---
 * Copyright (C) 2022 T-Systems International GmbH and all other contributors
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package eu.europa.ec.dgc.gateway.restapi.filter;

import eu.europa.ec.dgc.gateway.config.DgcConfigProperties;
import eu.europa.ec.dgc.gateway.entity.TrustedPartyEntity;
import eu.europa.ec.dgc.gateway.exception.DgcgResponseException;
import eu.europa.ec.dgc.gateway.restapi.mapper.CertificateRoleMapper;
import eu.europa.ec.dgc.gateway.service.TrustedPartyService;
import eu.europa.ec.dgc.gateway.utils.DgcMdc;
import eu.europa.ec.dgc.utils.CertificateUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Slf4j
@Component
@AllArgsConstructor
public class CertificateAuthenticationFilter extends OncePerRequestFilter {

    public static final String REQUEST_PROP_COUNTRY = "reqPropCountry";
    public static final String REQUEST_PROP_THUMBPRINT = "reqPropCertThumbprint";

    @Qualifier("requestMappingHandlerMapping")
    private final RequestMappingHandlerMapping requestMap;

    private final DgcConfigProperties properties;

    private final TrustedPartyService trustedPartyService;

    private final HandlerExceptionResolver handlerExceptionResolver;

    private final CertificateRoleMapper certificateRoleMapper;

    private final CertificateUtils certificateUtils;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        try {
            HandlerExecutionChain handlerExecutionChain = requestMap.getHandler(request);

            if (handlerExecutionChain == null) {
                return true;
            } else {
                return !((HandlerMethod) handlerExecutionChain.getHandler()).getMethod()
                    .isAnnotationPresent(CertificateAuthenticationRequired.class);
            }
        } catch (Exception e) {
            handlerExceptionResolver.resolveException(request, null, null, e);
            return false;
        }
    }

    private String normalizeCertificateHash(String inputString) {
        if (inputString == null) {
            return null;
        }

        boolean isHexString;
        // check if it is a hex string
        try {
            Hex.decode(inputString);
            isHexString = true;
        } catch (DecoderException ignored) {
            isHexString = false;
        }

        // We can assume that the given string is hex encoded SHA-256 hash when length
        // is 64 and string is hex encoded
        if (inputString.length() == 64 && isHexString) {
            return inputString;
        } else {
            try {
                String hexString;
                if (inputString.contains("%")) { // only url decode input string if it contains none base64 characters
                    inputString = URLDecoder.decode(inputString, StandardCharsets.UTF_8);
                }
                hexString = Hex.toHexString(Base64.getDecoder().decode(inputString));
                return hexString;
            } catch (IllegalArgumentException ignored) {
                log.error("Could not normalize certificate hash.");
                return null;
            }
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse,
        FilterChain filterChain) throws ServletException, IOException {
        logger.debug("Checking request for auth headers or auth certificate");

        String certThumbprint = null;
        String certSubject = null;

        String certSubjectHeaderValue =
            httpServletRequest.getHeader(properties.getCertAuth().getHeaderFields().getDistinguishedName());

        String certThumbprintHeaderValue =
            httpServletRequest.getHeader(properties.getCertAuth().getHeaderFields().getThumbprint());

        String certPemHeaderValue =
            httpServletRequest.getHeader(properties.getCertAuth().getHeaderFields().getPem());


        if (httpServletRequest.getUserPrincipal() != null) {
            log.debug("Found Client Certificate in request");

            PreAuthenticatedAuthenticationToken token = (PreAuthenticatedAuthenticationToken) httpServletRequest
                .getUserPrincipal();

            X509Certificate certificate = (X509Certificate) token.getCredentials();
            certSubject = certificate.getSubjectX500Principal().toString();
            certThumbprint = httpServletRequest.getUserPrincipal().getName();
        } 
                
        if (certSubjectHeaderValue != null && certThumbprintHeaderValue != null) {
            log.debug("Found Cert Subject and Thumbprint in Request Headers");

            certSubject = certSubjectHeaderValue;
            certThumbprint = normalizeCertificateHash(certThumbprintHeaderValue);
        }
        
        if (certPemHeaderValue != null) {
            log.debug("Found Cert PEM in Request Headers");

            X509Certificate x509Certificate = getCertificateFromPem(certPemHeaderValue);

            if (x509Certificate != null) {
                certSubject = x509Certificate.getSubjectX500Principal().toString();
                certThumbprint = certificateUtils.getCertThumbprint(x509Certificate);
            }
        }
       
        if (certSubject == null || certThumbprint == null) {
            log.error("No authentication information");
            handlerExceptionResolver.resolveException(
                httpServletRequest,
                httpServletResponse,
                null,
                new DgcgResponseException(
                    HttpStatus.UNAUTHORIZED,
                    "0x400",
                    "No authentication information available",
                    "", ""));
            return;
        }
        certSubject = URLDecoder.decode(certSubject, StandardCharsets.UTF_8);

        DgcMdc.put("dnString", certSubject);
        DgcMdc.put("thumbprint", certThumbprint);

        Map<String, String> distinguishNameMap = parseDistinguishNameString(certSubject);

        if (!distinguishNameMap.containsKey("C")) {
            log.error("Country property is missing");
            handlerExceptionResolver.resolveException(
                httpServletRequest, httpServletResponse, null,
                new DgcgResponseException(
                    HttpStatus.BAD_REQUEST,
                    "0x401",
                    "Client Certificate must contain country property",
                    certSubject, ""));
            return;
        }

        Optional<TrustedPartyEntity> certFromDb = trustedPartyService.getCertificate(
            certThumbprint,
            distinguishNameMap.get("C"),
            TrustedPartyEntity.CertificateType.AUTHENTICATION);

        if (certFromDb.isEmpty()) {
            log.error("Unknown client certificate");
            handlerExceptionResolver.resolveException(
                httpServletRequest, httpServletResponse, null,
                new DgcgResponseException(
                    HttpStatus.UNAUTHORIZED,
                    "0x402",
                    "Client is not authorized to access the service",
                    "", ""));

            return;
        } else if (certFromDb.get().getSourceGateway() != null) {
            log.error("Client Certificate is federated.");
            handlerExceptionResolver.resolveException(
                httpServletRequest, httpServletResponse, null,
                new DgcgResponseException(
                    HttpStatus.UNAUTHORIZED,
                    "0x402",
                    "Client is not authorized to access the service",
                    "", "Certificate is federated. "
                    + "Only certificates onboarded on this Gateway are allowed to authenticate"));

            return;
        }

        if (!checkRequiredRoles(httpServletRequest, certFromDb.get())) {
            log.error("Missing permissions to access this endpoint.");
            handlerExceptionResolver.resolveException(
                httpServletRequest, httpServletResponse, null,
                new DgcgResponseException(
                    HttpStatus.FORBIDDEN,
                    "0x403",
                    "Client is not authorized to access the endpoint",
                    "", ""));

            return;
        }

        log.info("Successful Authentication");
        httpServletRequest.setAttribute(REQUEST_PROP_COUNTRY, distinguishNameMap.get("C"));
        httpServletRequest.setAttribute(REQUEST_PROP_THUMBPRINT, certThumbprint);

        filterChain.doFilter(httpServletRequest, httpServletResponse);
    }

    private boolean checkRequiredRoles(HttpServletRequest request, TrustedPartyEntity entity) {
        HandlerExecutionChain handlerExecutionChain;
        try {
            handlerExecutionChain = requestMap.getHandler(request);
        } catch (Exception e) {
            log.error("Failed to extract required roles from request.");
            return false;
        }

        if (handlerExecutionChain == null) {
            log.error("Failed to extract required roles from request.");
            return false;
        }

        CertificateAuthenticationRole[] requiredRoles = ((HandlerMethod) handlerExecutionChain.getHandler())
            .getMethod().getAnnotation(CertificateAuthenticationRequired.class).requiredRoles();

        if (requiredRoles.length == 0) {
            log.debug("Endpoint requires no special roles.");
            return true;
        }

        for (CertificateAuthenticationRole requiredRole : requiredRoles) {
            if (!entity.getCertificateRoles().contains(certificateRoleMapper.dtoToEntity(requiredRole))) {
                log.error("Role {} is required to access endpoint", requiredRole.name());
                return false;
            }
        }

        return true;
    }

    /**
     * Parses a given Distinguish Name string (e.g. "C=DE,OU=Test Unit,O=Test
     * Company").
     *
     * @param dnString the DN string to parse.
     * @return Map with properties of the DN string.
     */
    private Map<String, String> parseDistinguishNameString(String dnString) {
        return Arrays.stream(dnString.split(","))
            .map(part -> part.split("="))
            .filter(entry -> entry.length == 2)
            .collect(Collectors.toMap(arr -> arr[0].toUpperCase().trim(), arr -> arr[1].trim(), (s, s2) -> s));
    }

    private X509Certificate getCertificateFromPem(String pem) {
        String decodedPem = URLDecoder.decode(pem, StandardCharsets.UTF_8);
        try (
            InputStream stream = new ByteArrayInputStream(decodedPem.getBytes(StandardCharsets.UTF_8))
        ) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            if (factory.generateCertificate(stream) instanceof X509Certificate x509Certificate) {
                return x509Certificate;
            } else {
                log.error("Provided PEM does not contain valid X509.Certificate.");
                return null;
            }
        } catch (IOException e) {
            log.error("Failed to read PEM");
            return null;
        } catch (CertificateException e) {
            log.error("Failed to parse PEM Content to Certificate");
            return null;
        }
    }
}
