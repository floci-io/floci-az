targetScope = 'subscription'

@minLength(1)
param environmentName string
param location string

var tags = { 'azd-env-name': environmentName }

resource rg 'Microsoft.Resources/resourceGroups@2021-04-01' = {
  name: 'rg-${environmentName}'
  location: location
  tags: tags
}

module app 'app.bicep' = {
  name: 'app'
  scope: rg
  params: {
    location: location
    tags: tags
  }
}

output AZURE_RESOURCE_GROUP string = rg.name
output SERVICE_WEB_NAME string = app.outputs.appName
output AZURE_CONTAINER_APPS_ENVIRONMENT_ID string = app.outputs.environmentId
