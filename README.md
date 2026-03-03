# Seats-allocation-service
Event seats allocation service for allocating event seats.

# API Endpoints
# Public 
GET /seats-allocation-service/v1/events/{eventId}/seats

POST /seats-allocation-service/v1/events/{eventId}/locks

POST /seats-allocation-service/v1/events/{eventId}/locks/release

# Internal
POST /seats-allocation-service/v1/events/{eventId}/inventory/init