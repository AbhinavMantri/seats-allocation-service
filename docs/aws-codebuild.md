# AWS CodeBuild Deployment

This service is packaged as a Spring Boot jar, containerized, pushed to Amazon ECR, and prepared for ECS deployment.

## Required CodeBuild variables

- `AWS_DEFAULT_REGION`: AWS region, for example `ap-south-1`.
- `ECR_REPOSITORY`: ECR repository name, for example `ticketmaster/seats-allocation-service`.

## Optional variables

- `AWS_ACCOUNT_ID`: If omitted, CodeBuild resolves it with STS.
- `IMAGE_TAG`: If omitted, the build uses `seats-allocation-service-<short-commit-sha>`.
- `CONTAINER_NAME`: ECS task definition container name. Defaults to `seats-allocation-service`.
- `DEPLOY_TO_ECS`: Set to `true` only when CodeBuild should trigger an ECS rolling deployment directly.
- `ECS_CLUSTER`: Required when `DEPLOY_TO_ECS=true`.
- `ECS_SERVICE`: Required when `DEPLOY_TO_ECS=true`.

## Pipeline shape

1. CodeBuild runs `./mvnw -B clean verify`.
2. CodeBuild builds the Docker image from `Dockerfile`.
3. CodeBuild pushes both the immutable tag and `latest` to ECR.
4. CodeBuild writes `imagedefinitions.json` for a CodePipeline ECS deploy action.
5. If `DEPLOY_TO_ECS=true`, CodeBuild also calls `aws ecs update-service --force-new-deployment`.
