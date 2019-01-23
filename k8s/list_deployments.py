from kubernetes import client, config
from os import path
import yaml
import sys



def main():
    # Configs can be set in Configuration class directly or using helper utility
    config.load_kube_config()

    v1 = client.CoreV1Api()
    print("Listing pods with their IPs:")
    ret = v1.list_pod_for_all_namespaces(watch=False)
    for i in ret.items:
        print("%s\t%s\t%s" % (i.status.pod_ip, i.metadata.namespace, i.metadata.name))

    print("")

    for i in filter_results(ret, "default"):
        print("%s\t%s\t%s" % (i.status.pod_ip, i.metadata.namespace, i.metadata.name))


def filter_results(seq, value):
    for e1 in seq.items:
        if e1.metadata.namespace==value:
            yield e1


if __name__ == '__main__':
    main()
    sys.exit(0)
