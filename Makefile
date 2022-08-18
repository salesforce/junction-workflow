IMAGES = vcsscanner viz worker
K8S_SERVICES = vcsscanner viz

build-base-image:
	docker build -t jw-base -f deploy/docker/Dockerfile.jw-base .

build-images: build-base-image
	for img in $(IMAGES); do \
		docker build -t jw-$$img -f deploy/docker/Dockerfile.jw-$$img .; \
	done

publish-images:
	for img in $(IMAGES); do \
		docker tag jw-$$img ${REGISTRY}/jw-$$img; \
		docker push ${REGISTRY}/jw-$$img; \
	done

apply-k8s:
	for img in $(K8S_SERVICES); do \
		kubectl apply -f deploy/aws/$$img.yaml; \
	done

deploy-k8s: apply-k8s
	for img in $(K8S_SERVICES); do \
		kubectl rollout restart deployment/$$img-deployment; \
	done
