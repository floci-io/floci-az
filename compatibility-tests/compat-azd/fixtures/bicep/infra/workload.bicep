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

output storageAccount string = store.name
output subnetRange string = cidrSubnet('10.20.0.0/16', 24, 1)
output identityCount int = length(filter(ids, id => !empty(id.id)))
