# syntax=docker/dockerfile:1
# Build context = gốc repo:  docker build -f services/notification/Dockerfile .
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY global.json Directory.Build.props Directory.Packages.props .editorconfig ./
COPY libs/dotnet/ libs/dotnet/
COPY services/notification/src/ services/notification/src/
RUN dotnet publish services/notification/src/Shp.Notification.Api/Shp.Notification.Api.csproj -c Release -o /app --no-self-contained

FROM mcr.microsoft.com/dotnet/aspnet:10.0-noble-chiseled
WORKDIR /app
COPY --from=build /app .
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080
USER $APP_UID
ENTRYPOINT ["dotnet", "Shp.Notification.Api.dll"]
