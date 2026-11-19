// The shape azd's container app templates provision: an environment, and an app tagged with the azd service name that
// azd deploy then rolls to the service's image.
param location string
param tags object

var token = take(uniqueString(resourceGroup().id), 8)

resource environment 'Microsoft.App/managedEnvironments@2025-01-01' = {
  name: 'cae-${token}'
  location: location
  tags: tags
  properties: {}
}

resource app 'Microsoft.App/containerApps@2025-01-01' = {
  name: 'ca-web-${token}'
  location: location
  tags: union(tags, { 'azd-service-name': 'web' })
  properties: {
    environmentId: environment.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 80
      }
      secrets: [
        { name: 'greeting', value: 'hello-from-floci' }
      ]
    }
    template: {
      containers: [
        {
          name: 'web'
          image: 'mcr.microsoft.com/k8se/quickstart:latest'
          env: [
            { name: 'GREETING', secretRef: 'greeting' }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 1
      }
    }
  }
}

output appName string = app.name
output environmentId string = environment.id
