// android_fsr.inc — AMD FidelityFX Super Resolution 1.0 at present time.
//
// #included by the Android patch into gpu/vk_renderer.cpp, just before
// RecordSwapchainBlit, because it is built from that file's own pieces (Image,
// CreateImage, RetireImage, Barrier, the frame slots) the way its gamma-ramp pass is.
//
// WHAT IT REPLACES. The present path scales the internal frame to the window with ONE
// linear vkCmdBlitImage. When the internal resolution is below the screen's — 720p on a
// 1440p phone, the normal case here — that blit is a soft bilinear stretch. With FSR on
// (DR2_FSR=1, the launcher's UPSCALER row) the frame instead goes through
//   EASU  edge-adaptive spatial upscaling, internal frame -> display-size image, then
//   RCAS  contrast-adaptive sharpening (DR2_FSR_SHARPNESS: stops, 0 = sharpest),
// two compute dispatches, and the result is copied 1:1 into the swapchain image. It
// only engages when the frame is actually being enlarged; any failure falls back to
// the blit, counted.
//
// Spatial only: no motion vectors, nothing in the game's frame changes. The shaders are
// native/fsr/dr2_fsr.comp, compiled by scripts/gen-fsr-shaders.sh.
#include "fsr/dr2_fsr_spv.h"

namespace dr2fsr
{
bool g_failed = false;
bool g_logged = false;
VkDescriptorSetLayout g_setLayout = VK_NULL_HANDLE;
VkPipelineLayout g_layout = VK_NULL_HANDLE;
VkPipeline g_easu = VK_NULL_HANDLE, g_rcas = VK_NULL_HANDLE;
VkSampler g_sampler = VK_NULL_HANDLE;
VkDescriptorPool g_pool = VK_NULL_HANDLE;
VkDescriptorSet g_sets[2][kMaxFramesInFlight] = {};
Image g_mid, g_out;

struct Push
{
    uint32_t con[4][4];
    uint32_t outSize[4];
};

// The launcher's choice comes in the environment at start; the in-game menu can change
// both while playing (NativeBridge.setFsr -> Dr2Android_FsrLive, android_main.cpp). The
// FSR resources are made on first use, so turning it on mid-game is just the next frame.
extern "C" int Dr2Android_FsrLive(float* sharpness);

bool Enabled()
{
    static const bool on = [] {
        const char* e = std::getenv("DR2_FSR");
        return e && e[0] == '1';
    }();
    float s;
    const int live = Dr2Android_FsrLive(&s);
    return live >= 0 ? live != 0 : on;
}

float SharpnessStops()
{
    static const float env = [] {
        const char* e = std::getenv("DR2_FSR_SHARPNESS");
        float v = e ? float(std::atof(e)) : 0.25f;
        return v < 0.f ? 0.f : (v > 2.f ? 2.f : v);
    }();
    float s = -1.f;
    Dr2Android_FsrLive(&s);
    return s >= 0.f ? (s > 2.f ? 2.f : s) : env;
}

uint32_t F2U(float f)
{
    uint32_t u;
    std::memcpy(&u, &f, 4);
    return u;
}

VkPipeline MakePipeline(const uint32_t* code, size_t bytes)
{
    VkShaderModuleCreateInfo ci{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
    ci.codeSize = bytes;
    ci.pCode = code;
    VkShaderModule m = VK_NULL_HANDLE;
    if (vkCreateShaderModule(R->device, &ci, nullptr, &m) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    VkComputePipelineCreateInfo pi{ VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO };
    pi.stage = { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO };
    pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pi.stage.module = m;
    pi.stage.pName = "main";
    pi.layout = g_layout;
    VkPipeline p = VK_NULL_HANDLE;
    if (vkCreateComputePipelines(R->device, R->pipeCache, 1, &pi, nullptr, &p) != VK_SUCCESS)
        p = VK_NULL_HANDLE;
    vkDestroyShaderModule(R->device, m, nullptr);
    return p;
}

bool Init()
{
    if (g_easu && g_rcas)
        return true;
    if (g_failed)
        return false;
    g_failed = true;   // until everything below succeeds

    VkDescriptorSetLayoutBinding b[2]{};
    b[0].binding = 0;
    b[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b[0].descriptorCount = 1;
    b[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    b[1].binding = 1;
    b[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[1].descriptorCount = 1;
    b[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo li{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
    li.bindingCount = 2;
    li.pBindings = b;
    if (vkCreateDescriptorSetLayout(R->device, &li, nullptr, &g_setLayout) != VK_SUCCESS)
        return false;

    VkPushConstantRange pr{ VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push) };
    VkPipelineLayoutCreateInfo pl{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &g_setLayout;
    pl.pushConstantRangeCount = 1;
    pl.pPushConstantRanges = &pr;
    if (vkCreatePipelineLayout(R->device, &pl, nullptr, &g_layout) != VK_SUCCESS)
        return false;

    VkSamplerCreateInfo si{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.maxLod = 0.f;
    if (vkCreateSampler(R->device, &si, nullptr, &g_sampler) != VK_SUCCESS)
        return false;

    const uint32_t nSets = 2 * kMaxFramesInFlight;
    VkDescriptorPoolSize ps[2] = { { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, nSets },
                                   { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, nSets } };
    VkDescriptorPoolCreateInfo pc{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
    pc.maxSets = nSets;
    pc.poolSizeCount = 2;
    pc.pPoolSizes = ps;
    if (vkCreateDescriptorPool(R->device, &pc, nullptr, &g_pool) != VK_SUCCESS)
        return false;
    VkDescriptorSetLayout layouts[2 * kMaxFramesInFlight];
    for (auto& l : layouts)
        l = g_setLayout;
    VkDescriptorSetAllocateInfo ai{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
    ai.descriptorPool = g_pool;
    ai.descriptorSetCount = nSets;
    ai.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(R->device, &ai, &g_sets[0][0]) != VK_SUCCESS)
        return false;

    g_easu = MakePipeline(kDr2FsrEasuSpv, sizeof kDr2FsrEasuSpv);
    g_rcas = MakePipeline(kDr2FsrRcasSpv, sizeof kDr2FsrRcasSpv);
    if (!g_easu || !g_rcas)
    {
        fprintf(stderr, "[vk] FSR: compute pipeline creation failed — presenting with the "
                        "plain blit\n");
        return false;
    }
    g_failed = false;
    return true;
}

bool EnsureImage(Image& img, uint32_t w, uint32_t h, VkImageUsageFlags extra)
{
    if (img.image && img.width == w && img.height == h)
        return true;
    if (img.image)
        RetireImage(img);
    img = Image{};
    if (!CreateImage(img, w, h, VK_FORMAT_R8G8B8A8_UNORM,
                     VK_IMAGE_USAGE_STORAGE_BIT | extra, VK_IMAGE_ASPECT_COLOR_BIT))
    {
        img = Image{};
        return false;
    }
    return true;
}

void Bind(VkDescriptorSet set, VkImageView in, VkImageLayout inLayout, VkImageView out)
{
    VkDescriptorImageInfo src{ g_sampler, in, inLayout };
    VkDescriptorImageInfo dst{ VK_NULL_HANDLE, out, VK_IMAGE_LAYOUT_GENERAL };
    VkWriteDescriptorSet wr[2]{};
    for (int i = 0; i < 2; ++i)
    {
        wr[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr[i].dstSet = set;
        wr[i].dstBinding = uint32_t(i);
        wr[i].descriptorCount = 1;
    }
    wr[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wr[0].pImageInfo = &src;
    wr[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    wr[1].pImageInfo = &dst;
    vkUpdateDescriptorSets(R->device, 2, wr, 0, nullptr);
}

// FSR `source` (its top-left w x h pixels are the frame) up to outW x outH. Returns the
// result in TRANSFER_SRC_OPTIMAL, ready to copy into the swapchain, or nullptr — the
// caller then blits as before.
Image* Upscale(Image& source, uint32_t w, uint32_t h, uint32_t outW, uint32_t outH)
{
    if (!w || !h || !outW || !outH || !source.view || !Init())
    {
        Count("fsr: unavailable, presented with the plain blit");
        return nullptr;
    }
    if (!EnsureImage(g_mid, outW, outH, VK_IMAGE_USAGE_SAMPLED_BIT) ||
        !EnsureImage(g_out, outW, outH, VK_IMAGE_USAGE_TRANSFER_SRC_BIT))
    {
        Count("fsr: image creation FAILED, presented with the plain blit");
        return nullptr;
    }
    if (!g_logged)
    {
        g_logged = true;
        fprintf(stderr, "[vk] FSR 1.0: %ux%u -> %ux%u (EASU + RCAS, sharpness %.2f stops)\n",
                w, h, outW, outH, double(SharpnessStops()));
    }
    const uint32_t slot = R->frameSlot;
    Bind(g_sets[0][slot], source.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, g_mid.view);
    Bind(g_sets[1][slot], g_mid.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, g_out.view);

    // EASU constants (ffx_fsr1.h FsrEasuCon).
    Push e{};
    const float iw = float(w), ih = float(h), sw = float(source.width), sh = float(source.height);
    const float ow = float(outW), oh = float(outH);
    e.con[0][0] = F2U(iw / ow);
    e.con[0][1] = F2U(ih / oh);
    e.con[0][2] = F2U(0.5f * iw / ow - 0.5f);
    e.con[0][3] = F2U(0.5f * ih / oh - 0.5f);
    e.con[1][0] = F2U(1.f / sw);
    e.con[1][1] = F2U(1.f / sh);
    e.con[1][2] = F2U(1.f / sw);
    e.con[1][3] = F2U(-1.f / sh);
    e.con[2][0] = F2U(-1.f / sw);
    e.con[2][1] = F2U(2.f / sh);
    e.con[2][2] = F2U(1.f / sw);
    e.con[2][3] = F2U(2.f / sh);
    e.con[3][0] = F2U(0.f);
    e.con[3][1] = F2U(4.f / sh);
    e.outSize[0] = outW;
    e.outSize[1] = outH;
    // RCAS constants (FsrRcasCon): sharpness in stops -> linear.
    Push r{};
    r.con[0][0] = F2U(std::exp2(-SharpnessStops()));
    r.outSize[0] = outW;
    r.outSize[1] = outH;
    const uint32_t gx = (outW + 15) / 16, gy = (outH + 15) / 16;

    Barrier(R->cmd, source, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);
    Barrier(R->cmd, g_mid, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT);
    vkCmdBindPipeline(R->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_easu);
    vkCmdBindDescriptorSets(R->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_layout, 0, 1,
                            &g_sets[0][slot], 0, nullptr);
    vkCmdPushConstants(R->cmd, g_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof e, &e);
    vkCmdDispatch(R->cmd, gx, gy, 1);

    Barrier(R->cmd, g_mid, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);
    Barrier(R->cmd, g_out, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT);
    vkCmdBindPipeline(R->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_rcas);
    vkCmdBindDescriptorSets(R->cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_layout, 0, 1,
                            &g_sets[1][slot], 0, nullptr);
    vkCmdPushConstants(R->cmd, g_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof r, &r);
    vkCmdDispatch(R->cmd, gx, gy, 1);

    Barrier(R->cmd, g_out, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);
    // The main path's state cache describes bindings this pass did not touch (compute
    // has its own bind point), but say so the same way the gamma-ramp pass does.
    R->bound = {};
    Count("fsr: applied at present");
    return &g_out;
}
} // namespace dr2fsr
