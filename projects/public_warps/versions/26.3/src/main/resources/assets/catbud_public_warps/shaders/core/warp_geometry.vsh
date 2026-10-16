#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
layout(location = 4) out vec4 lightMapColor;
layout(location = 5) out vec4 overlayColor;
layout(location = 6) out vec2 texCoord0;

void main() {
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    vec3 viewNormal = mat3(ModelViewMat) * Normal;
    if (dot(viewNormal, viewNormal) > 0.0) {
        viewNormal = normalize(viewNormal);
    }
    vec2 illumination = minecraft_compute_light(Light0_Direction, Light1_Direction, viewNormal);
    gl_Position = ProjMat * viewPosition;
    sphericalVertexDistance = fog_spherical_distance(viewPosition.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(viewPosition.xyz);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-illumination, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(illumination, Color);
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
