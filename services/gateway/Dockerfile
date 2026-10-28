# syntax=docker/dockerfile:1
# Build context = gốc repo:  docker build -f services/gateway/Dockerfile .
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY global.json Directory.Build.props Directory.Packages.props .editorconfig ./
COPY libs/dotnet/ libs/dotnet/
COPY services/gateway/src/ services/gateway/src/
RUN dotnet publish services/gateway/src/Shp.Gateway.Api/Shp.Gateway.Api.csproj -c Release -o /app --no-self-contained

FROM mcr.microsoft.com/dotnet/aspnet:10.0-noble-chiseled
WORKDIR /app
COPY --from=build /app .
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080
USER $APP_UID
ENTRYPOINT ["dotnet", "Shp.Gateway.Api.dll"]
