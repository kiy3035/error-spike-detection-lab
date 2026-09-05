param(
    [string]$ApplicationUrl = "http://localhost:8080",
    [string]$WireMockUrl = "http://localhost:8089"
)

# 애플리케이션 Actuator health를 확인합니다.
$health = Invoke-RestMethod -Uri "$ApplicationUrl/actuator/health" -Method Get
if ($health.status -ne "UP") {
    throw "Actuator health가 UP이 아닙니다: $($health.status)"
}
Write-Output "Actuator health: $($health.status)"

# 로컬 WireMock 가짜 알림 endpoint의 연결을 확인합니다.
$wireMockHealth = Invoke-RestMethod -Uri "$WireMockUrl/mock/health" -Method Get
Write-Output "WireMock health: $($wireMockHealth.status)"

# 다음 단계 전까지는 전송 로직 없이 stub의 HTTP 응답만 확인합니다.
$alertResponse = Invoke-WebRequest -Uri "$WireMockUrl/mock/alerts" -Method Post `
    -ContentType "application/json" -Body '{"alertId":"stage1-smoke"}'
if ([int]$alertResponse.StatusCode -ne 202) {
    throw "WireMock alert stub 상태 코드가 202가 아닙니다: $($alertResponse.StatusCode)"
}
Write-Output "WireMock alert stub: $($alertResponse.StatusCode)"
