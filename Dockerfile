FROM python:3.12-slim
LABEL org.opencontainers.image.version="1.7.3"
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home calendar && mkdir /data && chown calendar:calendar /data
COPY --chown=calendar:calendar app.py calendar_sync.py calendar_holidays.py ./
COPY --chown=calendar:calendar templates ./templates
COPY --chown=calendar:calendar static ./static
USER calendar
ENV DATA_DIR=/data PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["gunicorn", "--bind", "0.0.0.0:8000", "--workers", "1", "--threads", "4", "--access-logfile", "-", "app:app"]
