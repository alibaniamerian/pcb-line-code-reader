"""Observed ECC200 grids from low-contrast marks, strictly within the scan crop."""
import json
import cv2
import numpy as np
import grid_sampling as eg

cv2.setNumThreads(1)

def candidates(gray):
    h,w=gray.shape
    found=[]
    masks=[np.uint8(gray<t)*255 for t in (80,100,120,140,160,180,200)]
    masks.append(cv2.Canny(np.uint8(gray),25,70))
    masks += [cv2.morphologyEx(m,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8)) for m in masks]
    for mask in masks:
        contours,_=cv2.findContours(mask,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            rect=cv2.minAreaRect(contour);a,b=rect[1]
            if min(a,b)<24 or max(a,b)>.92*min(h,w) or max(a,b)/min(a,b)>1.35:continue
            box=cv2.boxPoints(rect).astype(float);center=box.mean(0)
            box=box[np.argsort(np.arctan2(box[:,1]-center[1],box[:,0]-center[0]))]
            if any(np.linalg.norm(center-q.mean(0))<4 and abs(np.linalg.norm(box[1]-box[0])-np.linalg.norm(q[1]-q[0]))<4 for q in found):continue
            found.append(box)
    found.sort(key=lambda q:np.linalg.norm(q.mean(0)-[w/2,h/2]))
    return found[:8]

def grids(data,width,height):
    gray=np.frombuffer(bytes(data),np.uint8).reshape(height,width).astype(np.float32)
    yy,xx=np.indices(gray.shape,dtype=float)
    design=np.stack([np.ones_like(xx),xx/width,yy/height,(xx/width)**2,xx*yy/(width*height),(yy/height)**2],-1)
    boards=[];seen=set()
    for box in candidates(gray):
        center=box.mean(0);size=np.mean(np.linalg.norm(box-np.roll(box,-1,0),axis=1))
        outside=np.ones(gray.shape,np.uint8)
        cv2.fillConvexPoly(outside,np.int32(center+(box-center)*1.18),0)
        mask=(outside>0)&(gray>np.percentile(gray,60))
        if mask.sum()<30:continue
        coef=np.linalg.lstsq(design[mask],gray[mask],rcond=None)[0]
        deviation=np.float32(design@coef-gray)
        for inset in (-1.,0.,1.,2.):
            q=np.float32(center+(box-center)*(size-2*inset)/size)
            for n in range(10,27,2):
                for stat in (10,'mean'):
                    values=eg.sample_board(deviation,q,n,n,.15,k=5,stat=stat)
                    for threshold in (3.,5.,8.,12.,18.,25.,35.,50.):
                        board=np.uint8(values>threshold);score,_=eg.finder_conformance(board)
                        if score<.95:continue
                        key=board.tobytes()
                        if key in seen:continue
                        seen.add(key);boards.append(dict(success=True,finder_conformance=score,board=board.tolist()))
        if len(boards)>100:break
    boards.sort(key=lambda b:-b['finder_conformance'])
    return json.dumps(boards[:150],allow_nan=False)

