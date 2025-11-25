#!/bin/bash

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 에러 발생 시 스크립트 중단
set -e

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Lee Yong Gwan Lee Service 배포 시작${NC}"
echo -e "${GREEN}========================================${NC}"

# 1. 프로젝트 디렉토리로 이동
echo -e "\n${YELLOW}[1/6] 프로젝트 디렉토리로 이동...${NC}"
cd "$(dirname "$0")/springProject"
pwd

# 2. Gradle 빌드
echo -e "\n${YELLOW}[2/6] Gradle 빌드 시작...${NC}"
./gradlew clean bootJar -x test
echo -e "${GREEN}✓ Gradle 빌드 완료${NC}"

# 3. Docker 이미지 빌드 (linux/amd64 플랫폼)
echo -e "\n${YELLOW}[3/6] Docker 이미지 빌드 중 (linux/amd64)...${NC}"
cd ..
docker buildx build --platform linux/amd64 -t ddingsh9/lee-yong-gwan-lee-service:1.0.0 -f springProject/Dockerfile springProject/ --load
echo -e "${GREEN}✓ Docker 이미지 빌드 완료${NC}"

# 4. Docker Hub에 푸시
echo -e "\n${YELLOW}[4/6] Docker Hub에 이미지 푸시 중...${NC}"
docker push ddingsh9/lee-yong-gwan-lee-service:1.0.0
echo -e "${GREEN}✓ Docker Hub 푸시 완료${NC}"
