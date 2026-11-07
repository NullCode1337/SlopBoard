#!/system/bin/sh
# KernelSU runs this inside the module directory before mounting.
set_perm_recursive "$MODPATH/system_ext" 0 0 0755 0644
