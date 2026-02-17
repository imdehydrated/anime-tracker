"""API routes for the ML sidecar."""

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

router = APIRouter()


# --- Request/Response models ---

class EmbedRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=10000)


class EmbedBatchRequest(BaseModel):
    texts: list[str] = Field(..., min_items=1, max_items=100)


class EmbedResponse(BaseModel):
    embedding: list[float]
    dimension: int


class EmbedBatchResponse(BaseModel):
    embeddings: list[list[float]]
    dimension: int
    count: int


class RerankRequest(BaseModel):
    query_embedding: list[float]
    candidate_ids: list[int]
    candidate_scores: list[float]
    top_k: int = Field(default=15, ge=1, le=100)


class RerankResponse(BaseModel):
    results: list[dict]


class CFRequest(BaseModel):
    user_ratings: dict[int, float] = Field(
        ...,
        description="Map of AniList ID to user score (1-10)"
    )
    exclude_ids: list[int] = Field(default_factory=list)
    top_k: int = Field(default=15, ge=1, le=100)


class CFResponse(BaseModel):
    predictions: list[dict]


# --- Routes ---

@router.post("/embed", response_model=EmbedResponse)
async def embed_text(request: Request, body: EmbedRequest):
    """Embed a single text using the fine-tuned anime semantic model."""
    model = request.app.state.semantic_model
    if model is None:
        raise HTTPException(503, "Semantic model not loaded")

    embedding = model.embed(body.text)
    return EmbedResponse(embedding=embedding, dimension=len(embedding))


@router.post("/embed/batch", response_model=EmbedBatchResponse)
async def embed_batch(request: Request, body: EmbedBatchRequest):
    """Embed multiple texts in a single batch."""
    model = request.app.state.semantic_model
    if model is None:
        raise HTTPException(503, "Semantic model not loaded")

    embeddings = model.embed_batch(body.texts)
    return EmbedBatchResponse(
        embeddings=embeddings,
        dimension=len(embeddings[0]) if embeddings else 0,
        count=len(embeddings)
    )


@router.post("/semantic/rerank", response_model=RerankResponse)
async def semantic_rerank(request: Request, body: RerankRequest):
    """Rerank pgvector candidates using the fine-tuned semantic model."""
    model = request.app.state.semantic_model
    if model is None:
        raise HTTPException(503, "Semantic model not loaded")

    results = model.rerank(
        query_embedding=body.query_embedding,
        candidate_ids=body.candidate_ids,
        candidate_scores=body.candidate_scores,
        top_k=body.top_k
    )
    return RerankResponse(results=results)


@router.post("/cf/recommend", response_model=CFResponse)
async def cf_recommend(request: Request, body: CFRequest):
    """Get CF predictions for a user based on their rating history."""
    model = request.app.state.cf_model
    if model is None:
        raise HTTPException(503, "CF model not loaded")

    predictions = model.predict(
        user_ratings=body.user_ratings,
        exclude_ids=body.exclude_ids,
        top_k=body.top_k
    )
    return CFResponse(predictions=predictions)
