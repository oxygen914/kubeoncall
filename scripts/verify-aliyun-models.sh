#!/usr/bin/env sh
set -eu

: "${ALIYUN_API_KEY:?Set ALIYUN_API_KEY to a Model Studio API key before running this script.}"

embedding_endpoint="${RAG_EMBEDDING_ENDPOINT:-https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings}"
embedding_model="${RAG_EMBEDDING_MODEL:-text-embedding-v4}"
embedding_dimensions="${RAG_EMBEDDING_DIMENSIONS:-1536}"
rerank_endpoint="${RAG_RERANK_ENDPOINT:-https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}"
rerank_model="${RAG_RERANK_MODEL:-qwen3-rerank}"
llm_endpoint="${MEMORY_LLM_EXTRACTION_ENDPOINT:-https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}"
llm_model="${MEMORY_LLM_EXTRACTION_MODEL:-qwen-plus}"

embedding="$(curl --silent --show-error --fail-with-body --max-time 30 --request POST "$embedding_endpoint" \
  --header "Authorization: Bearer $ALIYUN_API_KEY" \
  --header 'Content-Type: application/json' \
  --data "{\"model\":\"$embedding_model\",\"input\":\"KubeOnCall node CPU alert diagnosis\",\"dimensions\":$embedding_dimensions}")"
actual_dimensions="$(printf '%s' "$embedding" | jq -r '.data[0].embedding | length')"
[ "$actual_dimensions" = "$embedding_dimensions" ]
printf 'embedding: authenticated, model=%s, dimensions=%s\n' "$embedding_model" "$actual_dimensions"

rerank="$(curl --silent --show-error --fail-with-body --max-time 30 --request POST "$rerank_endpoint" \
  --header "Authorization: Bearer $ALIYUN_API_KEY" \
  --header 'Content-Type: application/json' \
  --data "{\"model\":\"$rerank_model\",\"input\":{\"query\":\"How should a node CPU alert be diagnosed?\",\"documents\":[\"Inspect CPU usage and offending processes.\",\"Inspect disk inode usage.\"]},\"parameters\":{\"top_n\":2,\"return_documents\":false}}")"
result_count="$(printf '%s' "$rerank" | jq -r '.output.results | length')"
[ "$result_count" -gt 0 ]
printf 'rerank: authenticated, model=%s, results=%s\n' "$rerank_model" "$result_count"

llm="$(curl --silent --show-error --fail-with-body --max-time 30 --request POST "$llm_endpoint" \
  --header "Authorization: Bearer $ALIYUN_API_KEY" \
  --header 'Content-Type: application/json' \
  --data "{\"model\":\"$llm_model\",\"temperature\":0,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with exactly: ok\"}]}")"
content="$(printf '%s' "$llm" | jq -r '.choices[0].message.content')"
[ "$content" = "ok" ]
printf 'llm: authenticated, model=%s, structured-chat-compatible=yes\n' "$llm_model"
