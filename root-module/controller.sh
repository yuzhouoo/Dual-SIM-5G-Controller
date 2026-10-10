#!/system/bin/sh
# All writes are confined to this module directory. Requires root (KernelSU/Magisk).
MODDIR=/data/adb/modules/dual_sim_5g
CONF="$MODDIR/enabled"
BACKUP="$MODDIR/original_masks"
STATE="$MODDIR/last_state"
LOG="$MODDIR/controller.log"
LOCK="$MODDIR/.lock"
NR=524288 # 1 << 19; Android NETWORK_TYPE_BITMASK_NR
umask 077
log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }
# get per-slot USER reason decimal masks from current active subscriptions only
read_active() {
  dumpsys isub 2>/dev/null | grep 'SubscriptionInfoInternal: id=' | grep 'simSlotIndex=[01]' | awk '
  { slot=""; id=""; mask="";
    if (match($0,/simSlotIndex=[01]/)) slot=substr($0,RSTART+13,1);
    if (match($0,/id=[0-9]+/)) id=substr($0,RSTART+3,RLENGTH-3);
    if (match($0,/user=[0-9]+/)) mask=substr($0,RSTART+5,RLENGTH-5);
    if (slot!="" && id!="" && mask!="") print slot, id, mask;
  }' | sort -u
}
# shell arithmetic and awk only (no Python dependency)
to_bin() { awk -v n="$1" 'BEGIN {s="";for(i=0;i<20;i++){s=(n%2) s;n=int(n/2)}print s}' ; }
get_slot_line() { echo "$1" | awk -v slot="$2" '$1==slot {print;exit}' ; }
valid_num() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
apply_slot() {
  slot="$1"; original="$2"; want_nr="$3"
  valid_num "$original" || return 1
  # Retain every supported bit except NR; avoid changing underlying 2G/3G/LTE selection.
  if [ "$want_nr" = 1 ]; then
    if [ $((original & NR)) -eq 0 ]; then target=$((original + NR)); else target="$original"; fi
  else
    target=$((original & ~NR))
  fi
  [ "$target" = "$original" ] && return 0
  bits=$(to_bin "$target")
  result=$(cmd phone set-allowed-network-types-for-users -s "$slot" "$bits" 2>&1)
  case "$result" in *'completed'*) log "slot=$slot nr=$want_nr target=$target success"; return 0;; *) log "slot=$slot ERROR $result"; return 1;; esac
}
once() {
  [ -e "$CONF" ] || return 0
  # Best effort lock: mkdir atomic; clear stale locks older than a minute in daemon.
  mkdir "$LOCK" 2>/dev/null || return 0
  trap 'rmdir "$LOCK" 2>/dev/null' EXIT INT TERM
  data=$(settings get global multi_sim_data_call 2>/dev/null)
  valid_num "$data" || { log "invalid default data subId"; return 1; }
  all=$(read_active)
  first=$(get_slot_line "$all" 0); second=$(get_slot_line "$all" 1)
  [ -n "$first" ] && [ -n "$second" ] || { log "not two active subscriptions; skip"; return 0; }
  set -- $first; id0="$2"; m0="$3"
  set -- $second; id1="$2"; m1="$3"
  if [ "$data" = "$id0" ]; then ds=0; elif [ "$data" = "$id1" ]; then ds=1; else log "default subId=$data does not map to active slots"; return 0; fi
  if [ ! -e "$BACKUP" ]; then
    printf '0 %s\n1 %s\n' "$m0" "$m1" > "$BACKUP"
    log "saved original user masks"
  fi
  # Enable NR on data SIM before removing it from the other, reducing interruption.
  if [ "$ds" = 0 ]; then
    apply_slot 0 "$m0" 1 || return 1
    apply_slot 1 "$m1" 0 || return 1
  else
    apply_slot 1 "$m1" 1 || return 1
    apply_slot 0 "$m0" 0 || return 1
  fi
  echo "dataSub=$data dataSlot=$ds slot0Sub=$id0 slot1Sub=$id1" > "$STATE"
}
restore() {
  rm -f "$CONF" # prevent daemon from instantly re-applying policy
  [ -f "$BACKUP" ] || { echo 'No backup exists'; return 1; }
  while read -r slot mask; do
    valid_num "$slot" && valid_num "$mask" || continue
    # Restore exact original mask, not just NR bit.
    result=$(cmd phone set-allowed-network-types-for-users -s "$slot" "$(to_bin "$mask")" 2>&1)
    echo "slot=$slot original=$mask $result"
  done < "$BACKUP"
}
case "$1" in
  once) once ;;
  daemon)
    count=0
    while :; do
      [ -e "$CONF" ] && once
      sleep 8
      count=$((count + 1))
      if [ "$count" -ge 30 ]; then count=0; [ -d "$LOCK" ] && rmdir "$LOCK" 2>/dev/null; fi
    done ;;
  enable) touch "$CONF"; once; echo 'enabled' ;;
  disable) rm -f "$CONF"; echo 'disabled; current modem modes unchanged' ;;
  restore) restore ;;
  status)
    if [ -e "$CONF" ]; then echo 'enabled=true'; else echo 'enabled=false'; fi
    echo "defaultDataSubId=$(settings get global multi_sim_data_call)"
    echo 'slot subId userMaskDecimal:'
    read_active
    echo 'slot0:'; cmd phone get-allowed-network-types-for-users -s 0
    echo 'slot1:'; cmd phone get-allowed-network-types-for-users -s 1
    [ -f "$STATE" ] && cat "$STATE"
    [ -f "$BACKUP" ] && { echo 'backup:'; cat "$BACKUP"; }
    [ -f "$LOG" ] && { echo 'recent log:'; tail -n 12 "$LOG"; }
    ;;
  *) echo 'Usage: controller.sh {once|daemon|enable|disable|restore|status}'; exit 2 ;;
esac
