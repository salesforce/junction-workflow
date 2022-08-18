# About

Junction Workflow is an upcoming workflow engine that uses compacted Kafka topics to manage 
state transitions of workflows that users pass into it. It is designed to take multiple workflow 
definition formats. 

It is currently under active development.

# Operator documentation
## Prerequisites
1. Moby or Docker installed
2. Kubernetes environment setup

## Build and Publish
First need to build your images and upload them to a registry that kubernetes can pull from
```sh-session
make build-images
REGISTRY=example.com make publish-images
```

## Deploy

```sh-session
kubectl create secret generic jw-gh-token \
    --from-literal=JW_GH_TOKEN=<your gh token>
kubectl create configmap jw-config --from-literal=JW_GH_ORGS=<comma-separated list of github orgs you'd like to scan>
make deploy-k8s
```


# Local developer documentation

## Requirements
1. Java version 17 from then set `JAVA_HOME` appropriately.
2. If you want to use CIX as one of the workflow declarations [Install CIX](https://opensource.salesforce.com/cix/#/getting-started/install?id=installing-from-dockerhub)

## Commandline build

```sh-session
$ ./gradlew build
```

## Create a github token and set it

Generate a personal access token by going to [https://github.com/settings/tokens](https://github.com/settings/tokens). 
It only needs these permissions: `public_repo, read:org, repo:invite, repo:status, repo_deployment` 

```
export JW_GH_ORGS=your-org-with-workflow-definitions,comma-separated
export JW_GH_TOKEN=<paste token from above>
```

## Run the E2ETest

The E2ETest will spin up all components, including an embedded local Kafka and put the UI on 
[http://localhost:8888](http://localhost:8888) by default. The test runs for exactly 5 minutes before it 
shuts itself down.

```sh-session
$ export JW_GH_ORGS=your-org-with-workflow-definitions-samples
$ export JW_GH_TOKEN=<paste token from above>
$ ./gradlew integrationTest
```

