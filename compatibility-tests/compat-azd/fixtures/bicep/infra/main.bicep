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

module workload 'workload.bicep' = {
  name: 'workload'
  scope: rg
  params: {
    location: location
    tags: tags
    identities: [ 'one', 'two' ]
  }
}

output AZURE_RESOURCE_GROUP string = rg.name
output STORAGE_ACCOUNT string = workload.outputs.storageAccount
output SUBNET_RANGE string = workload.outputs.subnetRange
output IDENTITY_COUNT int = workload.outputs.identityCount
