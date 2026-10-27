#!/usr/bin/env python3
"""Sinh khung (walking skeleton) cho một service .NET: API, test, Dockerfile, values local, workflow CI.

Dùng: python3 -I tools/scaffold/dotnet_service.py <name> [<name> ...]
Không ghi đè file đã tồn tại (dev đã sửa thì giữ nguyên).
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATES_REPO = "viethung12062005/shp-platform-templates-scratch"
TEMPLATES_REF = "work/initial-baseline"

# name -> (owner, mô tả, cổng local)
SERVICES = {
    "gateway": ("SA", "API gateway (YARP): định tuyến, xác thực JWT, rate limit", 8080),
    "scheduling": ("D1", "Lịch bác sĩ, slot, đặt/hủy lịch hẹn (FHIR Schedule/Slot/Appointment)", 8081),
    "notification": ("D1", "Nhắc lịch, gửi thông báo qua kênh, chống trùng", 8082),
    "clinical": ("D2", "Hồ sơ bệnh nhân, encounter, tìm kiếm lịch sử (FHIR)", 8083),
    "document": ("D2", "Tài liệu lâm sàng: link upload/tải có hạn, trạng thái quét", 8084),
    "audit": ("D2", "Nhận và tra cứu audit, chuỗi hash bất biến", 8085),
}


def pascal(name: str) -> str:
    return name[:1].upper() + name[1:]


def files(name: str) -> dict[str, str]:
    owner, desc, port = SERVICES[name]
    p = pascal(name)
    base = f"services/{name}"
    extra_pkg = '    <PackageReference Include="Yarp.ReverseProxy" />\n' if name == "gateway" else ""
    return {
        f"{base}/Shp.{p}.slnx": f"""<Solution>
  <Folder Name="/libs/">
    <Project Path="../../libs/dotnet/Shp.ServiceDefaults/Shp.ServiceDefaults.csproj" />
  </Folder>
  <Project Path="src/Shp.{p}.Api/Shp.{p}.Api.csproj" />
  <Project Path="tests/Shp.{p}.Tests/Shp.{p}.Tests.csproj" />
</Solution>
""",
        f"{base}/src/Shp.{p}.Api/Shp.{p}.Api.csproj": f"""<Project Sdk="Microsoft.NET.Sdk.Web">
  <PropertyGroup>
    <RootNamespace>Shp.{p}.Api</RootNamespace>
    <InvariantGlobalization>true</InvariantGlobalization>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="../../../../libs/dotnet/Shp.ServiceDefaults/Shp.ServiceDefaults.csproj" />
{extra_pkg}  </ItemGroup>
  <ItemGroup>
    <InternalsVisibleTo Include="Shp.{p}.Tests" />
  </ItemGroup>
</Project>
""",
        f"{base}/src/Shp.{p}.Api/Program.cs": f"""using Shp.ServiceDefaults;

var builder = WebApplication.CreateBuilder(args);
builder.AddServiceDefaults("{name}");

// TODO(dev): đăng ký service nghiệp vụ, DbContext/Mongo client, MassTransit consumer ở đây.

var app = builder.Build();
app.MapServiceDefaults();

// TODO(dev): map endpoint theo docs/03-architecture/detailed-design (mỗi nhóm endpoint một file Endpoints/*.cs).

await app.RunAsync().ConfigureAwait(false);

/// <summary>Điểm vào cho WebApplicationFactory trong test.</summary>
public partial class Program;
""",
        f"{base}/src/Shp.{p}.Api/appsettings.json": """{
  "Logging": { "LogLevel": { "Default": "Information", "Microsoft.AspNetCore": "Warning" } },
  "AllowedHosts": "*"
}
""",
        f"{base}/tests/Shp.{p}.Tests/Shp.{p}.Tests.csproj": f"""<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <IsPackable>false</IsPackable>
    <IsTestProject>true</IsTestProject>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.NET.Test.Sdk" />
    <PackageReference Include="xunit" />
    <PackageReference Include="xunit.runner.visualstudio" />
    <PackageReference Include="coverlet.collector" />
    <PackageReference Include="Microsoft.AspNetCore.Mvc.Testing" />
  </ItemGroup>
  <ItemGroup>
    <ProjectReference Include="../../src/Shp.{p}.Api/Shp.{p}.Api.csproj" />
  </ItemGroup>
</Project>
""",
        f"{base}/tests/Shp.{p}.Tests/HealthTests.cs": f"""using System.Net;
using Microsoft.AspNetCore.Mvc.Testing;
using Xunit;

namespace Shp.{p}.Tests;

public sealed class HealthTests(WebApplicationFactory<Program> factory) : IClassFixture<WebApplicationFactory<Program>>
{{
    [Theory]
    [InlineData("/health/live")]
    [InlineData("/health/ready")]
    [InlineData("/info")]
    public async Task Platform_endpoints_return_ok(string path)
    {{
        using var client = factory.CreateClient();
        using var response = await client.GetAsync(new Uri(path, UriKind.Relative));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }}
}}
""",
        f"{base}/Dockerfile": f"""# syntax=docker/dockerfile:1
# Build context = gốc repo:  docker build -f services/{name}/Dockerfile .
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY global.json Directory.Build.props Directory.Packages.props .editorconfig ./
COPY libs/dotnet/ libs/dotnet/
COPY services/{name}/src/ services/{name}/src/
RUN dotnet publish services/{name}/src/Shp.{p}.Api/Shp.{p}.Api.csproj -c Release -o /app --no-self-contained

FROM mcr.microsoft.com/dotnet/aspnet:10.0-noble-chiseled
WORKDIR /app
COPY --from=build /app .
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080
USER $APP_UID
ENTRYPOINT ["dotnet", "Shp.{p}.Api.dll"]
""",
        f"{base}/deploy/values-local.yaml": f"""# Values cho chart microservice khi chạy trên kind (make deploy SVC={name}).
image:
  repository: localhost/shp-{name}
  tag: dev
replicaCount: 1
env:
  ASPNETCORE_ENVIRONMENT: Development
  OTEL_SERVICE_NAME: {name}
""",
        f"{base}/README.md": f"""# {name}

{desc}. Chủ sở hữu: **{owner}**.

| Lệnh | Việc |
|---|---|
| `dotnet test services/{name}/Shp.{p}.slnx` | build + test |
| `dotnet run --project services/{name}/src/Shp.{p}.Api` | chạy local (dùng hạ tầng từ `make -C dev up`, biến môi trường từ `dev/scripts/env.sh`) |
| `make -C dev deploy SVC={name}` | build image và deploy lên cụm kind |

Endpoint nền có sẵn: `/health/live`, `/health/ready`, `/info`. Dev chỉ thêm logic nghiệp vụ trong `src/`.
""",
        f".github/workflows/svc-{name}.yml": f"""# Sinh bởi tools/scaffold/dotnet_service.py. Pipeline: CI → image (quét, SBOM, ký) → promote uat (chờ duyệt).
name: svc-{name}
on:
  pull_request:
    paths: ["services/{name}/**", "libs/dotnet/**", "Directory.*.props", "global.json", ".github/workflows/svc-{name}.yml"]
  push:
    branches: [main]
    paths: ["services/{name}/**", "libs/dotnet/**", "Directory.*.props", "global.json", ".github/workflows/svc-{name}.yml"]
permissions:
  contents: read
jobs:
  ci:
    uses: {TEMPLATES_REPO}/.github/workflows/dotnet-ci.yml@{TEMPLATES_REF}
    with:
      working-directory: services/{name}
  container:
    needs: ci
    uses: {TEMPLATES_REPO}/.github/workflows/container.yml@{TEMPLATES_REF}
    permissions:
      contents: read
      id-token: write
    with:
      context: .
      dockerfile: services/{name}/Dockerfile
      image-name: {name}
      push: ${{{{ github.ref == 'refs/heads/main' }}}}
      acr-name: ${{{{ vars.ACR_NAME }}}}
      azure-client-id: ${{{{ vars.AZURE_CLIENT_ID_ACR_PUSH }}}}
      azure-tenant-id: ${{{{ vars.AZURE_TENANT_ID }}}}
      azure-subscription-id: ${{{{ vars.AZURE_SUBSCRIPTION_ID }}}}
  promote-uat:
    if: github.ref == 'refs/heads/main'
    needs: container
    uses: {TEMPLATES_REPO}/.github/workflows/gitops-promote.yml@{TEMPLATES_REF}
    with:
      service: {name}
      environment: uat
      digest: ${{{{ needs.container.outputs.digest }}}}
      gitops-owner: viethung12062005
      gitops-repo: shp-gitops-scratch
      gitops-app-id: ${{{{ vars.GITOPS_APP_ID }}}}
      mode: commit
    secrets:
      gitops-app-private-key: ${{{{ secrets.GITOPS_APP_PRIVATE_KEY }}}}
""",
    }


def main(argv: list[str]) -> int:
    names = argv or list(SERVICES)
    for name in names:
        if name not in SERVICES:
            print(f"unknown service: {name}", file=sys.stderr)
            return 2
        for rel, content in files(name).items():
            path = ROOT / rel
            if path.exists():
                print(f"skip   {rel}")
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            print(f"create {rel}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
