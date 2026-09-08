#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# CICD Platform - Keycloak provisioning script
#
# Bootstraps the demo users and realm roles that are NOT part of the static
# realm import (cicd-platform-realm.json). The realm, roles, client and
# client scopes (including the `basic` scope, which carries the OIDC `sub`
# mapper) are declared in the imported realm file; this script is idempotent
# and only fills in users + role assignments + passwords.
#
# Demo credentials are supplied via environment variables (defaults below are
# for local development only, matching backend container expectations).
#
# Run from the Keycloak image:
#   docker compose --profile provision run --rm provision
# or directly:
#   /opt/keycloak/bin/kcadm.sh ...
# ============================================================================

KCADM="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
KC_REALM="${KC_REALM:-cicd-platform}"
KC_SERVER="${KC_SERVER:-http://localhost:8080}"
KC_ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

DEMO_ADMIN_USERNAME="${DEMO_ADMIN_USERNAME:-admin@example.local}"
DEMO_ADMIN_PASSWORD="${DEMO_ADMIN_PASSWORD:-admin-password}"
DEMO_DEVELOPER_USERNAME="${DEMO_DEVELOPER_USERNAME:-developer@example.local}"
DEMO_DEVELOPER_PASSWORD="${DEMO_DEVELOPER_PASSWORD:-developer-password}"
DEMO_VIEWER_USERNAME="${DEMO_VIEWER_USERNAME:-viewer@example.local}"
DEMO_VIEWER_PASSWORD="${DEMO_VIEWER_PASSWORD:-viewer-password}"

log() { printf '[provision] %s\n' "$*"; }

# --- Wait for Keycloak to come up -----------------------------------------
KC_HOST=${KC_SERVER#*://}
KD_HOST=${KC_HOST%%:*}
KD_PORT=${KC_HOST##*:}
log "waiting for Keycloak at ${KC_SERVER}"
for i in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/${KD_HOST}/${KD_PORT}") 2>/dev/null; then
    exec 3>&- 3<&- || true
    log "Keycloak is reachable"
    break
  fi
  if [ "$i" -eq 60 ]; then
    log "Keycloak did not become reachable in time"
    exit 1
  fi
  sleep 2
done

"$KCADM" config credentials --server "$KC_SERVER" --realm master \
  --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD"

# --- Ensure roles exist ----------------------------------------------------
for role in ADMIN DEVELOPER VIEWER; do
  if "$KCADM" get roles -r "$KC_REALM" -q name="$role" >/dev/null 2>&1; then
    log "role '$role' already exists"
  else
    "$KCADM" create roles -r "$KC_REALM" -s name="$role" -s description="CICD Platform $role role" >/dev/null
    log "created role '$role'"
  fi
done

# --- Ensure the public client exists --------------------------------------
CLIENT_ID=$("$KCADM" get clients -r "$KC_REALM" -q clientId=cicd-frontend --fields id --format csv 2>/dev/null | tail -n 1)
if [ -z "$CLIENT_ID" ]; then
  CLIENT_ID=$("$KCADM" create clients -r "$KC_REALM" --set clientId=cicd-frontend \
    --set name="CICD Platform Frontend" \
    --set publicClient=true \
    --set standardFlowEnabled=true \
    --set directAccessGrantsEnabled=false \
    --set 'redirectUris=["http://localhost:3000/*"]' \
    --set 'webOrigins=["http://localhost:3000"]' \
    --set 'attributes.pkce.code.challenge.method=S256' 2>/dev/null || true)
  log "provisioned client cicd-frontend"
else
  log "client cicd-frontend already exists ($CLIENT_ID)"
fi

# --- Demo users ------------------------------------------------------------
create_or_update_user() {
  local username="$1" email="$2" first="$3" last="$4"
  local uid
  uid=$("$KCADM" get users -r "$KC_REALM" -q username="$username" --fields id --format csv 2>/dev/null | tail -n 1)
  if [ -z "$uid" ]; then
    uid=$("$KCADM" create users -r "$KC_REALM" \
      --set username="$username" \
      --set email="$email" \
      --set emailVerified=true \
      --set enabled=true \
      --set firstName="$first" \
      --set lastName="$last")
    log "created user '$username' ($uid)"
  else
    log "user '$username' already exists ($uid)"
  fi
  echo "$uid"
}

set_user_password() {
  local username="$1" password="$2"
  "$KCADM" set-password -r "$KC_REALM" --username "$username" -p "$password"
  log "set password for '$username'"
}

assign_role() {
  local username="$1" role="$2"
  "$KCADM" add-roles -r "$KC_REALM" --uusername "$username" --rolename "$role" >/dev/null 2>&1 || true
  log "ensured role '$role' for '$username'"
}

ADMIN_UID=$(create_or_update_user "$DEMO_ADMIN_USERNAME" "$DEMO_ADMIN_USERNAME" "Admin" "User")
set_user_password "$DEMO_ADMIN_USERNAME" "$DEMO_ADMIN_PASSWORD"
assign_role "$DEMO_ADMIN_USERNAME" ADMIN

DEV_UID=$(create_or_update_user "$DEMO_DEVELOPER_USERNAME" "$DEMO_DEVELOPER_USERNAME" "Developer" "User")
set_user_password "$DEMO_DEVELOPER_USERNAME" "$DEMO_DEVELOPER_PASSWORD"
assign_role "$DEMO_DEVELOPER_USERNAME" DEVELOPER

VIEW_UID=$(create_or_update_user "$DEMO_VIEWER_USERNAME" "$DEMO_VIEWER_USERNAME" "Viewer" "User")
set_user_password "$DEMO_VIEWER_USERNAME" "$DEMO_VIEWER_PASSWORD"
assign_role "$DEMO_VIEWER_USERNAME" VIEWER

log "provisioning complete"