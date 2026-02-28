FROM python:3.11-slim
WORKDIR /app
COPY mental-health-screening/python-ml-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY mental-health-screening/python-ml-service/ .
EXPOSE 5001
CMD ["gunicorn", "app:app", "--bind", "0.0.0.0:5001", "--workers", "1", "--timeout", "120"]
