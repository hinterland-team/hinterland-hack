// map meters <-> floor plan pixels (docs/robot-setup.md section 1), ros-style: origin = the map position of the
// image's lower-left corner, resolution = meters per pixel, the image's rows go from top to bottom (y flipped).
// shared by the map view and the tests, no imports
export type FloorImage = { width: number; height: number; resolution: number; originX: number; originY: number };

// meters -> pixel (u to the right, v down from the top edge)
export function worldToPixel(img: FloorImage, x: number, y: number) {
  return { u: (x - img.originX) / img.resolution, v: img.height - (y - img.originY) / img.resolution };
}

export function pixelToWorld(img: FloorImage, u: number, v: number) {
  return { x: img.originX + u * img.resolution, y: img.originY + (img.height - v) * img.resolution };
}

// the floor plan's corners in map meters
export function imageBounds(img: FloorImage) {
  return {
    minX: img.originX,
    minY: img.originY,
    maxX: img.originX + img.width * img.resolution,
    maxY: img.originY + img.height * img.resolution,
  };
}
