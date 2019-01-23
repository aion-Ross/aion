from kubernetes import client, config
from kubernetes.client.rest import ApiException
from os import path
import yaml
import sys


config.load_kube_config()

max_num_deploy = 2
namespace = "default"

def deploy():
    with open(path.join(path.dirname(__file__), "aion_node.yaml")) as f:
        dep = yaml.safe_load(f)
        k8s_beta = client.ExtensionsV1beta1Api()
        
        try:
            resp = k8s_beta.create_namespaced_deployment(
            body=dep, namespace=namespace)
            print("Deployment created. status='%s'" % str(resp.status))
        except ApiException as e:
            print("Exception occured when attempting to create new deployment")
            print("Status: %s"  % str(e.status))
            print("Reason: %s" % str(e.reason))
            print("")
            print("Exception when calling ExtensionsV1beta1Api->create_namespaced_deployment: %s\n" % e)
            sys.exit(1)

def filter_results(seq, value):
    for e1 in seq.items:
        if e1.metadata.namespace==value:
            yield e1

def list_deployments():
    v1 = client.CoreV1Api()
    print("Listing pods with their IPs:")
    ret = v1.list_pod_for_all_namespaces(watch=False)

    return filter_results(ret, namespace)

def main():
    # Configs can be set in Configuration class directly or using helper
    # utility. If no argument provided, the config will be loaded from
    # default location.

    deployments = list_deployments()

    # Awkward but neccessary way to get number of elements in the deployment generator
    num_deployments = sum(1 for i in deployments)

    for i in deployments:
        print("%s\t%s\t%s" % (i.status.pod_ip, i.metadata.namespace, i.metadata.name))

    if(num_deployments < max_num_deploy):
        deploy()
    else:
        print("Desired number of deployments has been met: " + str(num_deployments))
        print("Updating oldest build with latest image")
        sys.exit(1)

if __name__ == '__main__':
    main()
    sys.exit(0)