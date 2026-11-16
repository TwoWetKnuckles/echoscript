<?php

namespace App\Controller;

use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class SiteController extends AbstractController
{
    #[Route('/', name: 'home', methods: ['GET'])]
    public function home(): Response
    {
        $path = $this->getParameter('kernel.project_dir').'/../../downloads/current.json';
        $release = json_decode(file_get_contents($path), true, flags: JSON_THROW_ON_ERROR);
        return $this->render('home.html.twig', ['release' => $release]);
    }
}
