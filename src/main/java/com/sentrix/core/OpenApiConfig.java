package com.sentrix.core;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {
    @Bean
    OpenAPI sentrixOpenApi() {
        return new OpenAPI().info(new Info().title("SentriX 백엔드 API").version("v1")
                .description("환경 등록 → 데이터 소스 저장·연결 테스트 → 서비스·메트릭·로그·요청 추적 조회 순서로 사용합니다. "
                        + "Try it out → Execute는 실행 중인 서버에 실제 요청을 보냅니다. POST/PUT은 설정을 저장하거나 검사를 실행합니다. "
                        + "시간 조회에는 저장소에 데이터가 있는 최근 UTC 구간을 입력하세요. 모델·장애 분석 API는 아직 미구현입니다."))
                .servers(List.of(new Server().url("/").description("현재 문서를 제공하는 서버")));
    }

    @Bean
    OpenApiCustomizer sentrixResponses() {
        return api -> {
            api.getComponents().addSchemas("ApiProblem", new ObjectSchema()
                        .addProperty("type", new StringSchema().format("uri"))
                        .addProperty("title", new StringSchema())
                        .addProperty("status", new IntegerSchema())
                        .addProperty("detail", new StringSchema())
                        .addProperty("instance", new StringSchema().format("uri"))
                        .addProperty("code", new StringSchema().description("관측 조회 오류에 포함될 수 있는 분류 코드"))
                        .addProperty("upstreamStatus", new IntegerSchema().description("저장소 HTTP 오류에 포함될 수 있는 상태 코드")));
            if (api.getTags() != null) api.getTags().removeIf(tag -> tag.getName().equals("Actuator"));
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
                if (path.equals("/actuator/health")) {
                    op.setTags(List.of("00. 서버 상태"));
                    op.setSummary("서버·DB 상태 확인");
                    op.setDescription("Java 서버와 DB의 상태입니다. 관측 대상 웹서비스나 모델 상태가 아닙니다.");
                    var health = new Content().addMediaType("application/json", new MediaType().schema(
                            new ObjectSchema().addProperty("status", new StringSchema())));
                    op.getResponses().addApiResponse("200", new ApiResponse().description("서버·DB 정상").content(health))
                            .addApiResponse("503", new ApiResponse().description("서버 또는 DB 비정상").content(health));
                    return;
                }
                if (!path.startsWith("/api/v1/")) return;
                op.getResponses().addApiResponse("400", problem("입력 형식·범위가 올바르지 않거나 허용되지 않은 값"));
                if (!path.equals("/api/v1/environments")) {
                    op.getResponses().addApiResponse("404", problem("환경 또는 요청한 설정·trace를 찾을 수 없음"));
                }
                if (path.endsWith("/collection-status")) op.getResponses().addApiResponse("503", problem("조회 작업 중단"));
                boolean query = path.endsWith("/service-map") || path.contains("/services") || path.endsWith("/metrics")
                        || (path.endsWith("/logs") || path.endsWith("/logs/volume")) || path.contains("/traces");
                if (query) {
                    op.getResponses().addApiResponse("409", problem("관측 저장소 미설정 또는 접근 대상이 현재 허용되지 않음"))
                            .addApiResponse("502", problem("관측 저장소 연결·응답 오류 또는 응답 크기 제한 초과"))
                            .addApiResponse("503", problem("조회 작업 중단"))
                            .addApiResponse("504", problem("관측 저장소 응답 시간 초과"));
                } else if (method.name().equals("POST") || method.name().equals("PUT")) {
                    op.getResponses().addApiResponse("409", problem("환경 이름 중복 또는 설정 동시 변경 충돌"));
                    if (path.endsWith("/test")) op.getResponses().addApiResponse("503", problem("연결 검사 작업 중단"));
                }
            }));
        };
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new Content()
                .addMediaType("application/problem+json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
    }
}
