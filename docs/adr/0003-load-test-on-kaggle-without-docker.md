# Load test on Kaggle without Docker

The baseline load test runs on a Kaggle CPU notebook so phase 2 can repeat the workflow on the same platform. Kaggle notebooks cannot run Docker (no daemon), so on Kaggle the service runs as a plain JAR against a PostgreSQL installed with `apt`, and the load generator (k6) runs in the same notebook against `localhost`. Docker images are for local development and grading only. The service and k6 share the notebook CPUs; compare each run against its recorded `results/env.txt` rather than assuming identical hardware.
