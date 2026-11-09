"""Standalone observed-cell sampling, extracted unchanged from the panel reader. No panel data."""
import cv2
import numpy as np

def bilinear(a, x, y):
    # Physical wall searches at native RAW scale can exceed OpenCV's 32767
    # destination-column limit. Chunk samples without changing their positions.
    if np.asarray(x).size>30000:
        xx=np.asarray(x).ravel();yy=np.asarray(y).ravel()
        return np.concatenate([bilinear(a,xx[i:i+30000],yy[i:i+30000]) for i in range(0,len(xx),30000)])
    return cv2.remap(a, np.float32(x).reshape(1, -1), np.float32(y).reshape(1, -1), cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_REPLICATE)[0]

def sample_board(d, quad, rows, cols, inset, k=3, stat='mean'):
    H = cv2.getPerspectiveTransform(np.float32([[0, 0], [1, 0], [1, 1], [0, 1]]), np.float32(quad))
    t = np.linspace(inset, 1 - inset, k)
    cu = (np.arange(cols)[:, None] + t[None]) / cols
    cv = (np.arange(rows)[:, None] + t[None]) / rows
    uu = np.repeat(np.repeat(cu[None, :, :, None], rows, 0), k, 3)
    vv = np.repeat(np.repeat(cv[:, None, None, :], cols, 1), k, 2)
    xy = cv2.perspectiveTransform(np.stack([uu, vv], -1).reshape(1, -1, 2).astype(np.float32), H)[0]
    s = bilinear(d, xy[:, 0], xy[:, 1]).reshape(rows, cols, k * k)
    return s.mean(-1) if stat == 'mean' else np.percentile(s, stat, axis=-1)

def finder_conformance(dark):
    best = (-1.0, 0)
    for rot in range(4):
        b = np.rot90(dark, rot)
        R, C = b.shape
        top = np.arange(C) % 2 == 0
        right = (R - 1 - np.arange(R)) % 2 == 0
        score = np.concatenate([b[:, 0] == 1, b[-1] == 1, b[0] == top, b[:, -1] == right]).mean()
        if score > best[0]:
            best = (float(score), rot)
    return best
