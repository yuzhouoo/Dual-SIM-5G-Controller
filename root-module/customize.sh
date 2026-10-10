#!/system/bin/sh
ui_print '- Dual SIM 5G Controller'
ui_print '- ROOT service installed. Enabled by default.'
touch "$MODPATH/enabled"
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/controller.sh" 0 0 0755
set_perm "$MODPATH/enabled" 0 0 0600
