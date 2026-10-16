#version 330
#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>


in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

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
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
