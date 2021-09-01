# Kubernetes Deployment
## Kafka & Zookeper setup
Before deploying Strimzi Kafka operator, let’s first create our kafka namespace:
```sh
kubectl create namespace kafka
```
Next we apply the Strimzi install files, including ClusterRoles, ClusterRoleBindings and some Custom Resource Definitions (CRDs). The CRDs define the schemas used for declarative management of the Kafka cluster, Kafka topics and users.
```sh
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
```
Then we create a new Kafka custom resource, which will give us a small persistent Apache Kafka Cluster with one node for each - Apache Zookeeper and Apache Kafka:

Apply the `Kafka` Cluster CR file
```sh
kubectl apply -f k8s/kafka-persistent-single.yaml -n kafka 
```
Create the `Topics` in Kafka Cluster
```sh
kubectl apply -f k8s/topics -n kafka 
```
## Surge App & Business App Setup

To deploy surge app and business app on cluster use following command:
```sh
kubectl apply -f k8s/kafka-persistent-single.yaml -n kafka 
```
**Note:** Make sure to edit persistant volume size according to requirement.

To check logs if both app is running or not use following command: 
```sh
kubectl get pods -n kafka (Note surge pod name)
kubectl logs -f pod-name -c business-app -n kafka  (To check business-app container logs)
kubectl logs -f pod-name -c surge-server -n kafka  (To check business-app container logs)
```