---
title: Deploy Guide
slug: deploy-guide
owner: ops
tags: [infra, guide]
redirect_from: [/old/deployment.md]
---

# Deploy Guide

How to deploy to production with zero downtime.

## Prerequisites

See [Kubernetes setup](../infra/kubernetes.md) and
[networking](../infra/networking.md).

## Rolling deploy

Use the standard rollout. Diagram: ![architecture](../infra/assets/diagram.svg)

## Rollback

Revert the commit and redeploy. History is in Git.
