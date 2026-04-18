#!/usr/bin/env python3
import os
import sys
import subprocess

DEV_KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0=="
EMULATOR_PORT = 4577

def build_connection_string(account_name: str) -> str:
    base = f"http://localhost:{EMULATOR_PORT}"
    return (
        f"DefaultEndpointsProtocol=http;"
        f"AccountName={account_name};"
        f"AccountKey={DEV_KEY};"
        f"BlobEndpoint={base}/{account_name};"
        f"QueueEndpoint={base}/{account_name}-queue;"
        f"TableEndpoint={base}/{account_name}-table;"
    )

def extract_arg(args, arg_name):
    for i, arg in enumerate(args):
        if arg == arg_name and i + 1 < len(args):
            return args[i+1]
        if arg.startswith(arg_name + "="):
            return arg.split("=", 1)[1]
    return None

def main():
    if len(sys.argv) > 1 and sys.argv[1] == "setup":
        print(f"Connection String: {build_connection_string('devstoreaccount1')}")
        print(f"Alias suggestion: alias az='python3 {os.path.abspath(__file__)}'")
        return

    account_name = extract_arg(sys.argv, "--account-name") or "devstoreaccount1"
    
    env = os.environ.copy()
    
    # Only inject if not already present
    if "AZURE_STORAGE_CONNECTION_STRING" not in env and "--connection-string" not in sys.argv:
        conn_str = build_connection_string(account_name)
        env["AZURE_STORAGE_CONNECTION_STRING"] = conn_str
        
    env["AZURE_STORAGE_ALLOW_HTTP"] = "true"
    # SSL bypass for SDKs using requests
    env["REQUESTS_CA_BUNDLE"] = ""
    # For some other SDKs
    env["PYTHONHTTPSVERIFY"] = "0"

    try:
        subprocess.run(["az"] + sys.argv[1:], env=env)
    except FileNotFoundError:
        print("Error: 'az' CLI not found. Please install the Azure CLI.")
        sys.exit(1)

if __name__ == "__main__":
    main()
