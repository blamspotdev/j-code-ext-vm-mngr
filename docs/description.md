Manage full-system QEMU x86 / x86_64 virtual machines to run software that has no
ARM build — for example Microsoft SQL Server, whose engine segfaults under user-mode
emulation but runs correctly inside a real emulated x86 guest. VMs run under software
emulation only (no KVM on Android), so they are slow but fully compatible. Each VM is
a self-contained qcow2 disk plus a JSON config, with configurable guest-to-host port
forwards so other tools (such as the SQL Client extension) can reach services inside
the VM. Includes a one-click "Set up SQL Server" helper that provisions an Ubuntu 22.04
cloud-image VM and auto-installs Microsoft SQL Server 2022 (Developer edition) on first
boot via cloud-init — no manual OS install — then exposes it at localhost:1433 for the
SQL Client. Requires the "x86 Virtualization (QEMU)" SDK entry to be installed first.
