// Written to reach what azd templates compile to: a user-defined type forces languageVersion 2.0, so symbolic
// resources, a loop read through its collection, and lambda and CIDR functions all run.
type tagSet = {
  *: string
}

param location string
param tags tagSet
param identities string[]

resource store 'Microsoft.Storage/storageAccounts@2023-01-01' = {
  name: 'azdstore${take(uniqueString(resourceGroup().id), 10)}'
  location: location
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  tags: tags
}

resource ids 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = [for name in identities: {
  name: 'id-${name}'
  location: location
  tags: tags
  dependsOn: [ store ]
}]

// Both are soft-deleted by Azure when their group goes, so azd down --purge purges them afterwards: the workspace by a
// force delete before the group delete, the API Management service through its deleted-services record after it.
resource logs 'Microsoft.OperationalInsights/workspaces@2022-10-01' = {
  name: 'log-${take(uniqueString(resourceGroup().id), 10)}'
  location: location
  tags: tags
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
  }
}

resource apim 'Microsoft.ApiManagement/service@2022-08-01' = {
  name: 'apim-${take(uniqueString(resourceGroup().id), 10)}'
  location: location
  tags: tags
  sku: { name: 'Consumption', capacity: 0 }
  properties: {
    publisherEmail: 'azd@example.com'
    publisherName: 'floci-az'
  }
}

output storageAccount string = store.name
output logWorkspace string = logs.name
output apimService string = apim.name
output subnetRange string = cidrSubnet('10.20.0.0/16', 24, 1)
output identityCount int = length(filter(ids, id => !empty(id.id)))
