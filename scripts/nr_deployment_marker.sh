#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

NR_GRAPHQL_ENDPOINT="${NEW_RELIC_GRAPHQL_ENDPOINT:-https://api.newrelic.com/graphql}"
ENTITY_GUID="${NEW_RELIC_ENTITY_GUID:-}"

resolve_entity_guid() {
  if [[ -n "${ENTITY_GUID}" ]]; then
    return 0
  fi

  local search_query
  if [[ -n "${NEW_RELIC_ACCOUNT_ID:-}" ]]; then
    search_query="name = '${OTEL_SERVICE_NAME}' AND accountId = ${NEW_RELIC_ACCOUNT_ID}"
  else
    search_query="name = '${OTEL_SERVICE_NAME}'"
  fi

  local gql_payload
  gql_payload="$(jq -n --arg q "${search_query}" '{
    query: "query($q: String!) { actor { entitySearch(query: $q) { results { entities { guid name } } } } }",
    variables: { q: $q }
  }')"

  local resp
  resp="$(curl -sS -m 10 -X POST "${NR_GRAPHQL_ENDPOINT}" \
    -H "Content-Type: application/json" \
    -H "API-Key: ${NEW_RELIC_API_KEY}" \
    -d "${gql_payload}")"

  ENTITY_GUID="$(printf '%s' "${resp}" | jq -r '.data.actor.entitySearch.results.entities[0].guid // empty')"
}

post_deployment_marker() {
  local commit_sha="${GITHUB_SHA:-$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo "unknown")}"
  local short_sha="${commit_sha:0:7}"
  local version="${DEPLOY_VERSION:-${short_sha}}"
  local deployer="${GITHUB_ACTOR:-$(whoami)}"
  local description
  description="$(git -C "${REPO_ROOT}" log -1 --pretty=%s 2>/dev/null || echo "Deploy ${version}")"
  local timestamp_ms
  timestamp_ms="$(( $(date +%s) * 1000 ))"

  local mutation_payload
  mutation_payload="$(jq -n \
    --arg guid "${ENTITY_GUID}" \
    --arg ver "${version}" \
    --arg user "${deployer}" \
    --arg desc "${description}" \
    --arg sha "${commit_sha}" \
    --argjson ts "${timestamp_ms}" \
    '{
      query: "mutation($guid: EntityGuid!, $ver: String!, $user: String, $desc: String, $sha: String, $ts: EpochMilliseconds) { changeTrackingCreateDeployment(deployment: { entityGuid: $guid, version: $ver, user: $user, description: $desc, commit: $sha, timestamp: $ts }) { deploymentId entityGuid version } }",
      variables: {
        guid: $guid,
        ver: $ver,
        user: $user,
        desc: $desc,
        sha: $sha,
        ts: $ts
      }
    }')"

  local resp
  resp="$(curl -sS -m 10 -X POST "${NR_GRAPHQL_ENDPOINT}" \
    -H "Content-Type: application/json" \
    -H "API-Key: ${NEW_RELIC_API_KEY}" \
    -d "${mutation_payload}")"

  local dep_id
  dep_id="$(printf '%s' "${resp}" | jq -r '.data.changeTrackingCreateDeployment.deploymentId // empty')"

  if [[ -z "${dep_id}" ]]; then
    local err_msg
    err_msg="$(printf '%s' "${resp}" | jq -c '.errors // empty')"
    emit_event ERROR "deploy.nr_marker.failed" entity_guid="${ENTITY_GUID}" version="${version}" errors="${err_msg}"
    return 1
  fi

  emit_event INFO "deploy.nr_marker.created" deployment_id="${dep_id}" entity_guid="${ENTITY_GUID}" version="${version}" commit="${commit_sha}" user="${deployer}"
}

run_nr_deployment_marker() {
  if [[ -z "${NEW_RELIC_API_KEY:-}" ]]; then
    emit_event WARN "deploy.nr_marker.skipped" reason="missing_new_relic_api_key"
    return 0
  fi

  instrument INFO "deploy.nr_marker.lookup_guid" resolve_entity_guid

  if [[ -z "${ENTITY_GUID}" ]]; then
    emit_event ERROR "deploy.nr_marker.failed" reason="entity_guid_not_found" service="${OTEL_SERVICE_NAME}"
    return 1
  fi

  instrument INFO "deploy.nr_marker.create" post_deployment_marker
}

instrument INFO "deploy.nr_marker.run" run_nr_deployment_marker

