# Third-party notices

Clavaris itself is proprietary (see `LICENSE`). This file lists the third-party code that ships
in its container image, with the licence each component declares, and the services that run
beside it. It is generated from the image's CycloneDX SBOM by `scripts/check-licences.py notices`
and checked in CI (`scripts/check-licences.py covered`) so a new dependency cannot ship without
appearing here; versions are as of the last regeneration. Where an artefact's own metadata omits
its licence, the one read from its pom is recorded in `infra/licences/licence-policy.json`, with
where it was read. Licence terms to quote to a customer
are in `docs/03-architecture/adr/0050-licence-review-and-redis-versus-valkey.md`.

## Java libraries and bundled code

| Component | Version | Licence | Class |
|---|---|---|---|
| accessors-smart | 2.6.0 | Apache-2.0 | permissive |
| angus-activation | 2.0.3 | BSD-3-Clause | permissive |
| annotations | 2.59.1 | Apache-2.0 | permissive |
| antlr4-runtime | 4.13.2 | BSD-3-Clause | permissive |
| apache5-client | 2.59.1 | Apache-2.0 | permissive |
| arns | 2.59.1 | Apache-2.0 | permissive |
| asm | 9.7.1 | BSD-3-Clause | permissive |
| aspectjweaver | 1.9.25.1 | EPL-2.0 | weak-copyleft |
| attoparser | 2.0.7.RELEASE | Apache-2.0 | permissive |
| auth | 2.59.1 | Apache-2.0 | permissive |
| aws-core | 2.59.1 | Apache-2.0 | permissive |
| aws-query-protocol | 2.59.1 | Apache-2.0 | permissive |
| aws-xml-protocol | 2.59.1 | Apache-2.0 | permissive |
| bcprov-jdk18on | 1.87 | MIT | permissive |
| brave | 6.3.1 | Apache-2.0 | permissive |
| brave-propagation-aws | 1.4.0 | Apache-2.0 | permissive |
| brave-propagation-tracecontext | 0.2.0 | Apache-2.0 | permissive |
| byte-buddy | 1.18.15 | Apache-2.0 | permissive |
| caffeine | 3.2.4 | Apache-2.0 | permissive |
| cbor | 5.0.0-alpha2 | Unlicense | permissive |
| checker-qual | 3.55.1 | MIT | permissive |
| checksums | 2.59.1 | Apache-2.0 | permissive |
| checksums-spi | 2.59.1 | Apache-2.0 | permissive |
| classmate | 1.7.3 | Apache-2.0 | permissive |
| commons-lang3 | 3.20.0 | Apache-2.0 | permissive |
| commons-logging | 1.3.6 | Apache-2.0 | permissive |
| content-type | 2.3 | Apache-2.0 | permissive |
| context-propagation | 1.2.1 | Apache-2.0 | permissive |
| context-slf4j | 6.3.1 | Apache-2.0 | permissive |
| crt-core | 2.59.1 | Apache-2.0 | permissive |
| datautilities | 1.1.0 | CC0 Universal | permissive |
| endpoints-spi | 2.59.1 | Apache-2.0 | permissive |
| error_prone_annotations | 2.49.0 | "Apache 2.0" | permissive |
| eventstream | 2.59.1 | Apache-2.0 | permissive |
| failureaccess | 1.0.3 | Apache-2.0 | permissive |
| flyway-core | 12.4.0 | Apache-2.0 | permissive |
| flyway-database-postgresql | 12.4.0 | Apache-2.0 | permissive |
| gson | 2.13.1 | Apache-2.0 | permissive |
| guava | 33.8.0-jre | Apache-2.0 | permissive |
| HdrHistogram | 2.2.2 | BSD-2-Clause; http://creativecommons.org/publicdomain/zero/1.0/ | permissive |
| hibernate-core | 7.4.13.Final | Apache-2.0 | permissive |
| hibernate-models | 1.1.2 | Apache-2.0 | permissive |
| hibernate-validator | 9.1.5.Final | Apache-2.0 | permissive |
| HikariCP | 7.0.2 | Apache-2.0 | permissive |
| http-auth | 2.59.1 | Apache-2.0 | permissive |
| http-auth-aws | 2.59.1 | Apache-2.0 | permissive |
| http-auth-aws-eventstream | 2.59.1 | Apache-2.0 | permissive |
| http-auth-spi | 2.59.1 | Apache-2.0 | permissive |
| http-client-spi | 2.59.1 | Apache-2.0 | permissive |
| httpclient5 | 5.6.5 | Apache-2.0 | permissive |
| httpcore5 | 5.4.4 | Apache-2.0 | permissive |
| httpcore5-h2 | 5.4.4 | Apache-2.0 | permissive |
| identity-spi | 2.59.1 | Apache-2.0 | permissive |
| instrumentation-http | 6.3.1 | Apache-2.0 | permissive |
| istack-commons-runtime | 4.1.2 | BSD-3-Clause | permissive |
| j2objc-annotations | 3.1 | Apache License, Version 2.0 | permissive |
| jackson-annotations | 2.23 | Apache-2.0 | permissive |
| jackson-core | 2.23.0 | Apache-2.0 | permissive |
| jackson-databind | 2.23.0 | Apache-2.0 | permissive |
| jackson-dataformat-cbor | 2.23.0 | Apache-2.0 | permissive |
| jackson-dataformat-yaml | 2.23.0 | Apache-2.0 | permissive |
| jackson-datatype-jdk8 | 2.23.0 | Apache-2.0 | permissive |
| jackson-datatype-jsr310 | 2.23.0 | Apache-2.0 | permissive |
| jakarta.activation-api | 2.1.4 | BSD-3-Clause | permissive |
| jakarta.annotation-api | 3.0.0 | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | weak-copyleft |
| jakarta.inject-api | 2.0.1 | Apache-2.0 | permissive |
| jakarta.persistence-api | 3.2.0 | EPL-2.0; BSD-3-Clause | permissive |
| jakarta.transaction-api | 2.0.1 | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | weak-copyleft |
| jakarta.validation-api | 3.1.1 | Apache-2.0 | permissive |
| jakarta.xml.bind-api | 4.0.5 | BSD-3-Clause | permissive |
| jaxb-core | 4.0.9 | BSD-3-Clause | permissive |
| jaxb-runtime | 4.0.9 | BSD-3-Clause | permissive |
| jboss-logging | 3.6.3.Final | https://repository.jboss.org/licenses/apache-2.0.txt | permissive |
| jcip-annotations | 1.0-1 | Apache License, Version 2.0 | permissive |
| jctools-core | 4.0.7 | Apache License, Version 2.0 | permissive |
| jctools-core-jdk11 | 4.0.7 | Apache License, Version 2.0 | permissive |
| jrt-fs | 25.0.5 | GPL-2.0-with-classpath-exception | weak-copyleft |
| json-smart | 2.6.0 | Apache-2.0 | permissive |
| json-utils | 2.59.1 | Apache-2.0 | permissive |
| jspecify | 1.0.1 | Apache-2.0 | permissive |
| jul-to-slf4j | 2.0.20 | MIT | permissive |
| lang-tag | 1.7 | Apache-2.0 | permissive |
| lettuce-core | 7.5.2.RELEASE | MIT | permissive |
| listenablefuture | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | permissive |
| log4j-api | 2.25.5 | "Apache-2.0" | permissive |
| log4j-to-slf4j | 2.25.5 | "Apache-2.0" | permissive |
| logback-classic | 1.5.38 | EPL-2.0; LGPL-2.1-or-later | weak-copyleft |
| logback-core | 1.5.38 | EPL-2.0; LGPL-2.1-or-later | weak-copyleft |
| metrics-spi | 2.59.1 | Apache-2.0 | permissive |
| micrometer-commons | 1.17.2 | Apache-2.0 | permissive |
| micrometer-core | 1.17.2 | Apache-2.0 | permissive |
| micrometer-jakarta9 | 1.17.2 | Apache-2.0 | permissive |
| micrometer-observation | 1.17.2 | Apache-2.0 | permissive |
| micrometer-registry-prometheus | 1.17.2 | Apache-2.0 | permissive |
| micrometer-tracing | 1.7.2 | Apache-2.0 | permissive |
| micrometer-tracing-bridge-brave | 1.7.2 | Apache-2.0 | permissive |
| netty-buffer | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec-base | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec-compression | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec-dns | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec-http | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec-http2 | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec-marshalling | 4.2.20.Final | Apache-2.0 | permissive |
| netty-codec-protobuf | 4.2.20.Final | Apache-2.0 | permissive |
| netty-common | 4.2.20.Final | Apache-2.0 | permissive |
| netty-handler | 4.2.20.Final | Apache-2.0 | permissive |
| netty-nio-client | 2.59.1 | Apache-2.0 | permissive |
| netty-resolver | 4.2.20.Final | Apache-2.0 | permissive |
| netty-resolver-dns | 4.2.20.Final | Apache-2.0 | permissive |
| netty-transport | 4.2.20.Final | Apache-2.0 | permissive |
| netty-transport-classes-epoll | 4.2.20.Final | Apache-2.0 | permissive |
| netty-transport-native-unix-common | 4.2.20.Final | Apache-2.0 | permissive |
| nimbus-jose-jwt | 10.9.1 | Apache-2.0 | permissive |
| numbers | 1.8.2 | CC0-1.0 | permissive |
| oauth2-oidc-sdk | 11.38.2 | Apache-2.0 | permissive |
| postgresql | 42.7.14 | BSD-2-Clause | permissive |
| profiles | 2.59.1 | Apache-2.0 | permissive |
| prometheus-metrics-config | 1.7.0 | Apache-2.0 | permissive |
| prometheus-metrics-core | 1.7.0 | Apache-2.0 | permissive |
| prometheus-metrics-exposition-formats | 1.7.0 | Apache-2.0 | permissive |
| prometheus-metrics-exposition-textformats | 1.7.0 | Apache-2.0 | permissive |
| prometheus-metrics-model | 1.7.0 | Apache-2.0 | permissive |
| prometheus-metrics-tracer-common | 1.7.0 | Apache-2.0 | permissive |
| protocol-core | 2.59.1 | Apache-2.0 | permissive |
| reactive-streams | 1.0.4 | MIT-0 | permissive |
| reactor-core | 3.8.8 | Apache-2.0 | permissive |
| redis-authx-core | 0.1.1-beta2 | MIT | permissive |
| regions | 2.59.1 | Apache-2.0 | permissive |
| resilience4j-circuitbreaker | 2.4.0 | Apache-2.0 | permissive |
| resilience4j-core | 2.4.0 | Apache-2.0 | permissive |
| retries | 2.59.1 | Apache-2.0 | permissive |
| retries-spi | 2.59.1 | Apache-2.0 | permissive |
| s3 | 2.59.1 | Apache-2.0 | permissive |
| sdk-core | 2.59.1 | Apache-2.0 | permissive |
| slf4j-api | 2.0.20 | MIT | permissive |
| snakeyaml | 2.6 | Apache-2.0 | permissive |
| spring-aop | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-aspects | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-beans | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-boot | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-actuator | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-actuator-autoconfigure | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-autoconfigure | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-data-commons | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-data-jpa | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-data-redis | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-flyway | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-health | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-hibernate | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-http-converter | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-jackson | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-jarmode-tools | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-jdbc | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-jpa | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-micrometer-metrics | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-micrometer-observation | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-micrometer-tracing | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-micrometer-tracing-brave | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-netty | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-persistence | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-security | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-security-oauth2-authorization-server | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-security-oauth2-client | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-security-oauth2-resource-server | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-servlet | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-sql | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-thymeleaf | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-tomcat | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-transaction | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-validation | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-web-server | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-webmvc | 4.1.2 | Apache-2.0 | permissive |
| spring-boot-zipkin | 4.1.2 | Apache-2.0 | permissive |
| spring-context | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-context-support | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-core | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-data-commons | 4.1.2 | Apache-2.0 | permissive |
| spring-data-jpa | 4.1.2 | Apache-2.0 | permissive |
| spring-data-keyvalue | 4.1.2 | Apache-2.0 | permissive |
| spring-data-redis | 4.1.2 | Apache-2.0 | permissive |
| spring-expression | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-jdbc | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-messaging | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-orm | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-oxm | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-security-config | 7.1.2 | Apache-2.0 | permissive |
| spring-security-core | 7.1.2 | Apache-2.0 | permissive |
| spring-security-crypto | 7.1.2 | Apache-2.0 | permissive |
| spring-security-oauth2-authorization-server | 7.1.2 | Apache-2.0 | permissive |
| spring-security-oauth2-client | 7.1.2 | Apache-2.0 | permissive |
| spring-security-oauth2-core | 7.1.2 | Apache-2.0 | permissive |
| spring-security-oauth2-jose | 7.1.2 | Apache-2.0 | permissive |
| spring-security-oauth2-resource-server | 7.1.2 | Apache-2.0 | permissive |
| spring-security-web | 7.1.2 | Apache-2.0 | permissive |
| spring-session-core | 4.1.1 | Apache-2.0 | permissive |
| spring-session-data-redis | 4.1.1 | Apache-2.0 | permissive |
| spring-tx | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-web | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| spring-webmvc | 7.0.10 | Apache-2.0; BSD-3-Clause | permissive |
| springdoc-openapi-starter-common | 3.2.0 | Apache-2.0 | permissive |
| springdoc-openapi-starter-webmvc-api | 3.2.0 | Apache-2.0 | permissive |
| springdoc-openapi-starter-webmvc-ui | 3.2.0 | Apache-2.0 | permissive |
| swagger-annotations-jakarta | 2.2.55 | "Apache License 2.0" | permissive |
| swagger-core-jakarta | 2.2.55 | "Apache License 2.0" | permissive |
| swagger-models-jakarta | 2.2.55 | "Apache License 2.0" | permissive |
| swagger-ui | 5.34.0 | Apache-2.0 | permissive |
| third-party-jackson-core | 2.59.1 | Apache-2.0 | permissive |
| thymeleaf | 3.1.5.RELEASE | Apache-2.0 | permissive |
| thymeleaf-spring6 | 3.1.5.RELEASE | Apache-2.0 | permissive |
| tomcat-embed-core | 11.0.29 | Apache-2.0 | permissive |
| tomcat-embed-el | 11.0.29 | Apache-2.0 | permissive |
| tomcat-embed-websocket | 11.0.29 | Apache-2.0 | permissive |
| txw2 | 4.0.9 | BSD-3-Clause | permissive |
| unbescape | 1.1.6.RELEASE | Apache-2.0 | permissive |
| utils | 2.59.1 | Apache-2.0 | permissive |
| utils-lite | 2.59.1 | Apache-2.0 | permissive |
| webauthn-server-core | 2.10.1 | BSD-2-Clause; BSD-3-Clause | permissive |
| webjars-locator-lite | 1.1.5 | MIT | permissive |
| yubico-util | 2.10.1 | BSD-2-Clause; BSD-3-Clause | permissive |
| zipkin-reporter | 3.5.3 | Apache-2.0 | permissive |
| zipkin-reporter-brave | 3.5.3 | Apache-2.0 | permissive |

Operating-system packages of the base image (Ubuntu) carry their own licences, listed in the
image's SBOM; they are separate programs the image aggregates, not code Clavaris links against.

## Services that run beside Clavaris

| Service | Image | Licence |
|---|---|---|
| PostgreSQL | `postgres` | PostgreSQL License (permissive) |
| Redis | `redis:8` | RSALv2, SSPLv1 or AGPLv3 at the user's choice (Redis 8); see ADR-0050 |
| Caddy | `caddy` | Apache-2.0 |
