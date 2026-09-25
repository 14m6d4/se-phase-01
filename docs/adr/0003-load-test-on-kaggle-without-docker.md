# Load test on Kaggle without Docker

The baseline load test must run on a Kaggle CPU notebook so that phase 2 can compare on identical hardware. Kaggle notebooks cannot run Docker (no daemon), so on Kaggle the service runs as a plain JAR against a PostgreSQL installed with `apt`, and the load generator (k6) runs in the same notebook against `localhost`. Docker images are for local development and grading only. Service and load generator share the same ~4 vCPUs; results must be read with that in mind.
