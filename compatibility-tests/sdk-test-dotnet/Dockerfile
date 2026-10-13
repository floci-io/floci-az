FROM mcr.microsoft.com/dotnet/sdk:10.0
WORKDIR /app

COPY SdkTestDotnet.csproj .
RUN dotnet restore

COPY *.cs .
RUN dotnet build --no-restore

RUN mkdir -p /results
# TUnit writes JUnit XML only when its reporter is enabled; --junit-output-path alone writes
# nothing, and CI then has no report to count or summarise.
ENV TUNIT_ENABLE_JUNIT_REPORTER=true
ENTRYPOINT ["dotnet", "run", "--no-build", "--", "--junit-output-path", "/results/servicebus-dotnet.xml"]
