# J Code — VM Manager extension

VM Manager is a J Code app extension for creating and running full-system **QEMU
x86 / x86_64 virtual machines** on an ARM64 Android device. It is the UI for managing
VMs used to run software that ships no ARM build.

The frontend runs in a WebView and drives `qemu-system-x86_64` / `qemu-img` inside the
active Linux distro. You can create a VM (name, RAM, CPUs, disk size, optional install
ISO, port forwards), start it headless and detached, stop it, view its serial console,
and delete it. Each VM is stored under `/root/vms/<name>/` as a `disk.qcow2` image plus
a `vm.json` config.

## Requirements

Install the **"x86 Virtualization (QEMU)"** entry (id `qemu-system-x86`) from the **SDK
Manager** before using this extension — it provides `qemu-system-x86` and `qemu-utils`
(`qemu-img`) in the active distro. VM Manager checks for both on load and warns if
they are missing.

## Slow but compatible

Android does not expose KVM, so VMs run under **pure software emulation** (QEMU TCG,
`-accel tcg`). Expect them to be **slow**, but **fully compatible** — the guest runs a
real x86_64 kernel and userland.

## Use case: SQL Server in a VM

Install **Microsoft SQL Server** inside an x86_64 VM, forward guest TCP `1433` to a host
port, then connect with the **SQL Client** extension at `localhost:1433`. The VM handles
the x86 workload; the SQL Client connects over a normal TCP socket.

## License

MIT License. See [LICENSE](LICENSE).
