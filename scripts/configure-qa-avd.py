"""Declare the disposable CI phone's memory profile before Android starts."""
from pathlib import Path
import sys

path = Path(sys.argv[1])
assert path.is_file() and path.name == "config.ini"
values = {"hw.ramSize": "3072", "vm.heapSize": "128"}
lines = [line for line in path.read_text().splitlines() if line.split("=", 1)[0].strip() not in values]
path.write_text("\n".join(lines + [f"{key}={value}" for key, value in values.items()]) + "\n")
print("Declared disposable CI phone: 3072 MiB RAM, 128 MiB managed heap.")
