# interview-demo

Kétkomponensű demo alkalmazás **Kubernetesre**:
- **frontend** (statikus webapp)
- **backend** (Spring Boot, Java 21) – `/api/hello` végponttal, ami válaszol, melyik **pod** szolgálta ki (Downward API env-ekkel)

CI/CD: **GitHub Actions** → build & push a **GHCR**-be → **self-hosted runner** deploy → **Kustomize**.  
Elérés: **Ingress NGINX** (NodePort), host: `demo.local`.

---

## Tartalomjegyzék
- [Architektúra](#architektúra)
- [Előkövetelmények](#előkövetelmények)
- [Telepítés Kubernetesre](#telepítés-kubernetesre)
- [Elérés böngészőből](#elérés-böngészőből)
- [CI/CD](#cicd)
- [K8s komponensek](#k8s-komponensek)
- [Fejlesztői smoke test](#fejlesztői-smoke-test)
- [Hasznos parancsok](#hasznos-parancsok)
- [Hibaelhárítás](#hibaelhárítás)
- [További lépések](#további-lépések)
- [Licenc](#licenc)

---

## Architektúra

```
GitHub → CI → ghcr.io/slaca93/interview-demo-{frontend,backend}:{sha|latest}
                                  │
                                  ▼
                      Deploy (self-hosted runner)
                                  │
                             Kubernetes
      +-------------------+-------------------+
      | frontend (2 pod)  | backend (2 pod)   |
      +-------------------+-------------------+
                   ▲             ▲
                   └──── Ingress ┘  (nginx, NodePort 31080)
```

Képek:
- `ghcr.io/slaca93/interview-demo-frontend`
- `ghcr.io/slaca93/interview-demo-backend`

---

## Előkövetelmények
- Kubernetes klaszter + `kubectl`
- **kustomize**
- **Ingress NGINX** telepítve **NodePort** módban (HTTP: `31080`, HTTPS: `31443`)
- GitHub Actions **Workflow permissions**: _Read and write permissions_
- (Deployhoz) self-hosted runner `k8s-deployer` labellel, `kubectl`/`kustomize` elérhető PATH-ban és működő kubeconfiggal

---

## Telepítés Kubernetesre

> A repo K8s fájljai a `k8s/` alatt vannak. Production overlay: `k8s/overlays/prod`.

1) Namespace és manifesztek:
```bash
kubectl create ns interview-demo || true
kustomize build k8s/overlays/prod | kubectl apply -f -
```

2) Ingress (ha külön fájlban tartod):
```bash
kubectl apply -f k8s/overlays/prod/ingress.yaml
```

> Ingress mögött a **frontend** Service legyen `ClusterIP`. Ha még NodePort:
```bash
kubectl -n interview-demo patch svc frontend -p '{"spec":{"type":"ClusterIP"}}'
```

---

## Elérés böngészőből

Ingress NGINX **NodePort**:
1) kérj egy node IP-t:
```bash
kubectl get nodes -o wide
```
2) add a hosts fájlodhoz:
```
<node-ip>  demo.local
```
3) böngésző:
```
http://demo.local:31080/          # frontend
http://demo.local:31080/api/hello # backend – a válaszban: pod=<név> ip=<IP>
```

CLI-ből (hosts módosítás nélkül):
```bash
curl -H 'Host: demo.local' http://<node-ip>:31080/
curl -H 'Host: demo.local' http://<node-ip>:31080/api/hello
```

---

## CI/CD

### CI – build & push ( `.github/workflows/ci.yml` )
- Futtatás: `push` és `pull_request` `main`, `develop`, `feature/**`
- Lépések:
  - **Frontend**: Node 20 → `npm ci && npm run build` → Docker build → push `ghcr.io/slaca93/interview-demo-frontend:{sha}` (+ `latest` csak `main`-en)
  - **Backend**: Java 21 → `mvn -Dmaven.test.skip=true package` → Docker build → push `ghcr.io/slaca93/interview-demo-backend:{sha}` (+ `latest` csak `main`-en)
- Az image-neveknél minden **kisbetű**.

### Deploy ( `.github/workflows/deploy.yml` )
- Futtatás: `push` `main` **és/vagy** kézzel `workflow_dispatch`
- Runner: `[ self-hosted, k8s-deployer ]`
- Lépések: `kustomize build k8s/overlays/prod | kubectl apply -f -` → rollout wait

**Tipp (stabil kiadás):** pineld a képeket **SHA tagre** deploy közben:
```bash
kubectl -n interview-demo set image deploy/frontend frontend=ghcr.io/slaca93/interview-demo-frontend:${GITHUB_SHA}
kubectl -n interview-demo set image deploy/backend  backend=ghcr.io/slaca93/interview-demo-backend:${GITHUB_SHA}
```

---

## K8s komponensek

- **Namespace:** `interview-demo`
- **Deployments:** `frontend` (2), `backend` (2)
- **Services:**  
  - `frontend` – `ClusterIP:80` (Ingress mögött)  
  - `backend`  – `ClusterIP:8080`
- **Ingress:** `app` – host: `demo.local`  
  - `/`     → `frontend:80`  
  - `/api`  → `backend:8080`

**Backend env (pod azonosításhoz):**
```yaml
env:
  - name: POD_NAME
    valueFrom: { fieldRef: { fieldPath: metadata.name } }
  - name: POD_IP
    valueFrom: { fieldRef: { fieldPath: status.podIP } }
```
**Java endpoint:**
```java
@GetMapping("/api/hello")
public String hello() {
  return "Hello from backend! pod=" + System.getenv("POD_NAME")
       + " ip=" + System.getenv("POD_IP");
}
```

---

## Fejlesztői smoke test

```bash
# Frontend (ha van build script)
cd frontend
npm ci && npm run build

# Backend
cd backend
mvn -Dmaven.test.skip=true package
java -jar target/*.jar
# böngésző: http://localhost:8080/api/hello
```

---

## Hasznos parancsok

```bash
# állapot összefoglaló
kubectl -n interview-demo get deploy,svc,pod,ingress -o wide

# rollout
kubectl -n interview-demo rollout status deploy/frontend
kubectl -n interview-demo rollout status deploy/backend

# gyors image váltás
kubectl -n interview-demo set image deploy/frontend frontend=ghcr.io/slaca93/interview-demo-frontend:<TAG>
kubectl -n interview-demo set image deploy/backend  backend=ghcr.io/slaca93/interview-demo-backend:<TAG>

# logok
kubectl -n interview-demo logs deploy/backend --tail=200
kubectl -n interview-demo logs deploy/frontend --tail=200
```

---

## Hibaelhárítás

- **`InvalidImageName` / `ImagePullBackOff`**  
  - az image neve legyen **kisbetűs** (`ghcr.io/slaca93/...`)  
  - a package publikus-e? (vagy adj `imagePullSecret`-et)
- **Backend `CrashLoopBackOff`**  
  - log: fut-e a Boot JAR? kell a `spring-boot-maven-plugin` + `package`  
  - Dockerfile másolja a `target/*.jar`-t `app.jar`-ként és `ENTRYPOINT ["java","-jar","/app/app.jar"]`
- **Ingress 404 / host mismatch**  
  - `kubectl -n interview-demo describe ingress app`  
  - `kubectl get ingressclass` (név: `nginx`)  
  - hosts fájlban legyen `demo.local → <node-ip>`
- **Runner nem veszi fel a jobot**  
  - Runner **Online**? Címkék: `self-hosted`, `k8s-deployer`  
  - DNS/443 kimenő forgalom engedélyezve? Idő szinkronban?

---

## További lépések
- **SHA-pin** beépítése a deploy workflow-ba (ne `latest`)  
- **HPA** (CPU/ memória alapján autoscaling)  
- **TLS**: cert-manager + Let’s Encrypt  
- **Observability**: Prometheus/Grafana, Loki, vagy alap `kubectl logs` + `events`

---

## Licenc
MIT
