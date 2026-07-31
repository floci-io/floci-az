FROM mcr.microsoft.com/dotnet/sdk:10.0
WORKDIR /app

COPY SdkTestDotnet.csproj .
RUN dotnet restore

COPY ServiceBusCompatibilityTests.cs .
RUN dotnet build --no-restore

RUN mkdir -p /results
ENTRYPOINT ["dotnet", "run", "--no-build", "--", "--junit-output-path", "/results/servicebus-dotnet.xml"]
