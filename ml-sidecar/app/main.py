"""AniRec ML Sidecar — FastAPI service for semantic + CF recommendations."""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.semantic_model import SemanticModel
from app.cf_model import CFModel
from app.routes import router

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Global model instances
semantic_model: SemanticModel | None = None
cf_model: CFModel | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load models on startup."""
    global semantic_model, cf_model

    logger.info("Loading models...")

    try:
        semantic_model = SemanticModel()
        logger.info("Semantic model loaded")
    except Exception as e:
        logger.warning(f"Failed to load semantic model: {e}")
        semantic_model = None

    try:
        cf_model = CFModel()
        logger.info("CF model loaded")
    except Exception as e:
        logger.warning(f"Failed to load CF model: {e}")
        cf_model = None

    # Store in app state for route access
    app.state.semantic_model = semantic_model
    app.state.cf_model = cf_model

    logger.info("Model loading complete")
    yield

    logger.info("Shutting down")


app = FastAPI(
    title="AniRec ML Sidecar",
    version="1.0.0",
    lifespan=lifespan
)

app.include_router(router)


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "semantic_model": app.state.semantic_model is not None,
        "cf_model": app.state.cf_model is not None,
    }
